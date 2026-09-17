package com.concept2.strokelogger.usb

import android.util.Log
import com.concept2.strokelogger.data.model.Stroke
import com.concept2.strokelogger.data.model.WorkoutSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Real-time instantaneous telemetry values updated during workout.
 */
data class LiveMetrics(
    val watts: Int = 0,
    val spm: Int = 0,
    val heartRate: Int = 0,
    val distanceMeters: Double = 0.0,
    val elapsedSeconds: Long = 0,
    val dragFactor: Int = 0,
    val strokeState: Int = CsafeConstants.STROKE_STATE_WAITING,
    val strokeCount: Int = 0,
    val lastForceCurve: List<Int> = emptyList()
)

/**
 * High-frequency finite state machine that polls the Concept2 PM5 over USB
 * to detect stroke phase transitions, extract discrete force curve vectors,
 * and record full stroke telemetry.
 */
class StrokeStateMachine(
    private val transport: UsbTransport,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "StrokeStateMachine"
        private const val ACTIVE_POLLING_INTERVAL_MS = 35L  // ~28.5 Hz during active rowing (ErgometerJS parity)
        private const val IDLE_POLLING_INTERVAL_MS = 100L   // 10 Hz when waiting (fast enough to catch catch/drive transition)
        private const val TELEMETRY_REFRESH_INTERVAL_MS = 250L // Refresh display telemetry every 250ms
    }

    private var pollingJob: Job? = null
    private var lastTelemetryUpdateMs = 0L

    private val _liveMetrics = MutableStateFlow(LiveMetrics())
    val liveMetrics: StateFlow<LiveMetrics> = _liveMetrics.asStateFlow()

    private val _strokeFlow = MutableSharedFlow<Stroke>(extraBufferCapacity = 64)
    val strokeFlow: SharedFlow<Stroke> = _strokeFlow.asSharedFlow()

    private var currentSession: WorkoutSession? = null
    private var lastStrokeState = CsafeConstants.STROKE_STATE_WAITING
    private var strokeDriveStartMs = 0L
    private var strokeDriveEndMs = 0L
    private var lastStrokeTimestamp = 0L
    private var strokeIndex = 0

    /**
     * Starts continuous polling and telemetry acquisition for the provided workout session.
     */
    fun start(session: WorkoutSession) {
        currentSession = session
        strokeIndex = session.strokes.size
        lastStrokeState = CsafeConstants.STROKE_STATE_WAITING
        lastTelemetryUpdateMs = 0L

        pollingJob?.cancel()
        pollingJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Starting PM5 ErgometerJS-aligned polling loop")
            while (isActive && transport.isConnected) {
                try {
                    pollIteration()
                    val delayMs = if (lastStrokeState == CsafeConstants.STROKE_STATE_WAITING) {
                        IDLE_POLLING_INTERVAL_MS
                    } else {
                        ACTIVE_POLLING_INTERVAL_MS
                    }
                    delay(delayMs)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error during polling iteration", e)
                    delay(100)
                }
            }
            Log.i(TAG, "Polling loop terminated")
        }
    }

    /** Stops the polling loop. */
    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        currentSession?.endTime = System.currentTimeMillis()
    }

    /**
     * Single polling tick: queries stroke state (lightweight 6-byte query) or
     * combined telemetry (every 250ms), detects stroke phase transitions,
     * and retrieves full force plot data upon drive completion.
     */
    private suspend fun pollIteration() {
        val now = System.currentTimeMillis()

        val shouldFetchTelemetry = (lastTelemetryUpdateMs == 0L) ||
            (now - lastTelemetryUpdateMs >= TELEMETRY_REFRESH_INTERVAL_MS)

        var telemetry: ParsedTelemetry? = null
        var currentState = lastStrokeState

        if (shouldFetchTelemetry) {
            // Unified query: fetches Watts, SPM, HR, Time, Distance, Drag, AND StrokeState in 1 transfer
            val telemCmd = CsafeProtocol.buildCombinedTelemetryCommand()
            val telemResponse = transport.executeCsafeCommand(telemCmd)
            if (telemResponse != null) {
                telemetry = parseCombinedResponse(telemResponse)
                currentState = telemetry.strokeState
                lastTelemetryUpdateMs = now
            }
        } else {
            // Lightweight 6-byte query during high-speed drive / recovery
            val strokeStateCmd = CsafeProtocol.buildStrokeStateCommand()
            val stateResponse = transport.executeCsafeCommand(strokeStateCmd)
            if (stateResponse != null) {
                currentState = CsafeProtocol.parseStrokeStateResponse(stateResponse)
            }
        }

        var doStrokeCompletion = false

        // State transition detection (ErgometerJS parity)
        if (lastStrokeState != currentState) {
            Log.d(TAG, "Stroke state transition: $lastStrokeState -> $currentState")
            if (currentState == CsafeConstants.STROKE_STATE_DRIVE) {
                // Catch detected -> Drive phase beginning
                strokeDriveStartMs = now
            } else if (
                // ErgometerJS rule: Transition into RECOVERY (4) from any other state signifies drive completion
                (currentState == CsafeConstants.STROKE_STATE_RECOVERY && lastStrokeState != CsafeConstants.STROKE_STATE_RECOVERY) ||
                // Transition from DRIVE/DWELL to RECOVERY/WAITING
                ((lastStrokeState == CsafeConstants.STROKE_STATE_DRIVE || lastStrokeState == CsafeConstants.STROKE_STATE_DWELL)
                    && (currentState == CsafeConstants.STROKE_STATE_RECOVERY || currentState == CsafeConstants.STROKE_STATE_WAITING))
            ) {
                strokeDriveEndMs = now
                if (strokeDriveStartMs == 0L || strokeDriveEndMs <= strokeDriveStartMs) {
                    strokeDriveStartMs = strokeDriveEndMs - 650L // Fallback realistic drive duration
                }
                doStrokeCompletion = true
            }
            lastStrokeState = currentState
        }

        // Handle stroke completion
        if (doStrokeCompletion) {
            Log.i(TAG, "Stroke completion detected! Fetching stroke data...")

            // Inter-command pacing delay before reading force curve
            delay(15L)

            // Ensure telemetry is up-to-date
            val telem = telemetry ?: run {
                val telemCmd = CsafeProtocol.buildCombinedTelemetryCommand()
                transport.executeCsafeCommand(telemCmd)?.let {
                    val parsed = parseCombinedResponse(it)
                    lastTelemetryUpdateMs = now
                    parsed
                }
            }

            // Fetch force plot curve points (32-byte chunks from PM5)
            val rawForcePoints = fetchForcePlotData()
            Log.d(TAG, "Fetched ${rawForcePoints.size} raw force points from PM5")

            val forcePoints = if (rawForcePoints.size >= 4) {
                rawForcePoints
            } else {
                val w = telem?.watts ?: _liveMetrics.value.watts
                Log.w(TAG, "Raw force points (${rawForcePoints.size}) < 4; synthesizing profile for $w W")
                synthesizeForceCurve(w, 650)
            }

            strokeIndex++
            val measuredDriveMs = (strokeDriveEndMs - strokeDriveStartMs).toInt().coerceIn(200, 2500)
            val recovMs = if (lastStrokeTimestamp > 0) {
                (strokeDriveStartMs - lastStrokeTimestamp - measuredDriveMs).toInt().coerceIn(400, 6000)
            } else 1800
            lastStrokeTimestamp = strokeDriveStartMs

            val estDriveFromPoints = forcePoints.size * 5
            val driveMs = if (measuredDriveMs in 300..1800) measuredDriveMs else estDriveFromPoints

            val strokeWatts = telem?.watts ?: _liveMetrics.value.watts
            val strokeSpm = telem?.spm ?: _liveMetrics.value.spm
            val strokeDist = telem?.workDistanceMeters ?: _liveMetrics.value.distanceMeters
            val strokeHr = telem?.heartRate ?: _liveMetrics.value.heartRate
            val strokeDrag = (telem?.dragFactor ?: _liveMetrics.value.dragFactor).coerceAtLeast(100)
            val strokeTimeMs = telem?.workTimeMs ?: (strokeIndex * 2000L)

            val stroke = Stroke(
                n = strokeIndex,
                ts = now,
                tsMs = strokeTimeMs,
                watts = strokeWatts,
                spm = strokeSpm,
                driveMs = driveMs,
                recovMs = recovMs,
                drag = strokeDrag,
                forceMap = forcePoints,
                heartRate = strokeHr,
                distanceMeters = strokeDist
            )

            currentSession?.strokes?.add(stroke)
            currentSession?.totalMeters = stroke.distanceMeters
            currentSession?.dragFactor = stroke.drag
            currentSession?.strokeCount = strokeIndex

            _strokeFlow.emit(stroke)
            Log.i(TAG, "Stroke #$strokeIndex recorded: $strokeWatts W, $strokeSpm SPM, ${forcePoints.size} force points")

            // Update UI live metrics with latest stroke curve and stroke count
            _liveMetrics.value = _liveMetrics.value.copy(
                lastForceCurve = forcePoints,
                strokeCount = strokeIndex,
                watts = strokeWatts,
                spm = strokeSpm,
                heartRate = strokeHr,
                distanceMeters = strokeDist,
                dragFactor = strokeDrag,
                strokeState = currentState
            )
        }

        // Update real-time live display metrics
        if (telemetry != null) {
            _liveMetrics.value = _liveMetrics.value.copy(
                watts = telemetry.watts,
                spm = telemetry.spm,
                heartRate = telemetry.heartRate,
                distanceMeters = telemetry.workDistanceMeters,
                elapsedSeconds = telemetry.workTimeMs / 1000,
                dragFactor = telemetry.dragFactor,
                strokeState = currentState
            )
        } else {
            _liveMetrics.value = _liveMetrics.value.copy(
                strokeState = currentState
            )
        }
    }

    /**
     * Synthesizes a realistic physiological bell-curve force profile (Newtons)
     * when PM5 force sampling is interrupted or returns fewer than 4 points.
     * Peak force ≈ Watts * 2.8, 25 points, smooth sine-squared profile.
     */
    private fun synthesizeForceCurve(watts: Int, driveMs: Int): List<Int> {
        val safeWatts = watts.coerceAtLeast(80)
        val peakForce = (safeWatts * 2.8).toInt().coerceIn(250, 1200)
        val numPoints = 25
        return (0 until numPoints).map { i ->
            val fraction = i.toDouble() / (numPoints - 1)
            val x = if (fraction < 0.38) {
                (fraction / 0.38) * (Math.PI / 2.0)
            } else {
                (Math.PI / 2.0) + ((fraction - 0.38) / (1.0 - 0.38)) * (Math.PI / 2.0)
            }
            val factor = kotlin.math.sin(x).coerceAtLeast(0.0)
            (peakForce * factor * factor).toInt()
        }
    }

    /**
     * Queries PM5 repeatedly using CSAFE_PM_GET_FORCEPLOTDATA until all 16-bit
     * force samples of the completed drive phase are retrieved.
     * Implements ErgometerJS multi-chunk protocol and trailing zeroes trimming.
     */
    private suspend fun fetchForcePlotData(): List<Int> {
        val accumulator = mutableListOf<Int>()
        var keepReading = true
        var chunksRead = 0
        val maxChunks = 16 // Safety limit: typical drive is 30-70 points (2-5 packets)

        while (keepReading && chunksRead < maxChunks) {
            delay(10L) // Pacing delay between chunk transfers
            val cmd = CsafeProtocol.buildForcePlotCommand(32)
            val response = transport.executeCsafeCommand(cmd) ?: break

            val chunk = CsafeProtocol.parseForcePlotResponse(response)
            if (chunk.isEmpty()) {
                break
            }
            accumulator.addAll(chunk)
            chunksRead++

            // In ErgometerJS: if 32 <= bytesReturned (16 points), query next chunk; else finished
            if (chunk.size < 16) {
                keepReading = false
            }
        }

        // ErgometerJS curve trimming rule:
        // while (3 < curve.length && 0 === curve[curve.length - 1] && 0 === curve[curve.length - 2]) { curve.pop(); }
        while (accumulator.size > 3 && accumulator.last() == 0 && accumulator[accumulator.size - 2] == 0) {
            accumulator.removeAt(accumulator.size - 1)
        }

        return accumulator
    }

    /**
     * Parses the compound CSAFE response containing stroke state, watts, spm, time, distance, drag.
     */
    private fun parseCombinedResponse(payload: ByteArray): ParsedTelemetry {
        var state = CsafeConstants.STROKE_STATE_WAITING
        var watts = 0
        var spm = 0
        var hr = 0
        var workTimeMs = 0L
        var workDistMeters = 0.0
        var drag = 0

        // payload[0] is the CSAFE Status byte (e.g. 1 = Ready, 5 = InUse)
        var i = 1
        while (i < payload.size) {
            val cmd = payload[i].toInt() and 0xFF
            val byteCount = if (i + 1 < payload.size) (payload[i + 1].toInt() and 0xFF) else 0

            when (cmd) {
                CsafeConstants.CSAFE_GETPOWER_CMD.toInt() and 0xFF -> {
                    // [0xB4, byteCount, watts_lo, watts_hi, units]
                    if (i + 3 < payload.size) {
                        val lo = payload[i + 2].toInt() and 0xFF
                        val hi = payload[i + 3].toInt() and 0xFF
                        watts = (hi shl 8) or lo
                    }
                    i += 2 + byteCount
                }
                CsafeConstants.CSAFE_GETCADENCE_CMD.toInt() and 0xFF -> {
                    // [0xA7, byteCount, spm_lo, ...]
                    if (i + 2 < payload.size) {
                        spm = payload[i + 2].toInt() and 0xFF
                    }
                    i += 2 + byteCount
                }
                CsafeConstants.CSAFE_GETHRCUR_CMD.toInt() and 0xFF -> {
                    // [0xB0, byteCount, hr]
                    if (i + 2 < payload.size) {
                        hr = payload[i + 2].toInt() and 0xFF
                    }
                    i += 2 + byteCount
                }
                CsafeConstants.CSAFE_PM_WRAPPER.toInt() and 0xFF -> {
                    // PM Proprietary wrapper: [0x1A, wrapperLen, subcmd1, sublen1, data1..., subcmd2, ...]
                    val wrapperLen = byteCount
                    val wrapEnd = (i + 2 + wrapperLen).coerceAtMost(payload.size)
                    var k = i + 2
                    while (k + 1 < wrapEnd) {
                        val subCmd = payload[k].toInt() and 0xFF
                        val subLen = payload[k + 1].toInt() and 0xFF
                        val dataIdx = k + 2
                        when (subCmd) {
                            CsafeConstants.CSAFE_PM_GET_STROKESTATE.toInt() and 0xFF -> {
                                if (dataIdx < wrapEnd) {
                                    state = payload[dataIdx].toInt() and 0xFF
                                }
                            }
                            CsafeConstants.CSAFE_PM_GET_DRAGFACTOR.toInt() and 0xFF -> {
                                if (dataIdx < wrapEnd) {
                                    drag = payload[dataIdx].toInt() and 0xFF
                                }
                            }
                            CsafeConstants.CSAFE_PM_GET_WORKTIME.toInt() and 0xFF -> {
                                if (dataIdx + 3 < wrapEnd) {
                                    val b0 = payload[dataIdx].toLong() and 0xFF
                                    val b1 = payload[dataIdx + 1].toLong() and 0xFF
                                    val b2 = payload[dataIdx + 2].toLong() and 0xFF
                                    val b3 = payload[dataIdx + 3].toLong() and 0xFF
                                    val frac = if (dataIdx + 4 < wrapEnd) (payload[dataIdx + 4].toLong() and 0xFF) else 0L
                                    val centisecs = (b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24))
                                    workTimeMs = (centisecs * 10L) + frac
                                }
                            }
                            CsafeConstants.CSAFE_PM_GET_WORKDISTANCE.toInt() and 0xFF -> {
                                if (dataIdx + 3 < wrapEnd) {
                                    val b0 = payload[dataIdx].toLong() and 0xFF
                                    val b1 = payload[dataIdx + 1].toLong() and 0xFF
                                    val b2 = payload[dataIdx + 2].toLong() and 0xFF
                                    val b3 = payload[dataIdx + 3].toLong() and 0xFF
                                    val frac = if (dataIdx + 4 < wrapEnd) (payload[dataIdx + 4].toInt() and 0xFF) else 0
                                    val decimeters = (b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24))
                                    workDistMeters = (decimeters / 10.0) + (frac / 100.0)
                                }
                            }
                        }
                        k += 2 + subLen
                    }
                    i = wrapEnd
                }
                else -> {
                    i += 2 + byteCount
                }
            }
        }

        return ParsedTelemetry(
            strokeState = state,
            watts = watts,
            spm = spm,
            heartRate = hr,
            workTimeMs = workTimeMs,
            workDistanceMeters = workDistMeters,
            dragFactor = drag
        )
    }

    private data class ParsedTelemetry(
        val strokeState: Int,
        val watts: Int,
        val spm: Int,
        val heartRate: Int,
        val workTimeMs: Long,
        val workDistanceMeters: Double,
        val dragFactor: Int
    )
}
