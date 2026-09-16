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
        private const val IDLE_POLLING_INTERVAL_MS = 400L   // 2.5 Hz when waiting for wheel speed
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
     * Single polling tick: queries stroke state (lightweight 6-byte query),
     * refreshes telemetry periodically (every 250ms), and retrieves full force
     * plot data upon drive completion.
     */
    private suspend fun pollIteration() {
        val now = System.currentTimeMillis()

        // 1. Query Stroke State (ErgometerJS high-resolution update)
        val strokeStateCmd = CsafeProtocol.buildStrokeStateCommand()
        val stateResponse = transport.executeCsafeCommand(strokeStateCmd)
        val currentState = if (stateResponse != null) {
            CsafeProtocol.parseStrokeStateResponse(stateResponse)
        } else {
            lastStrokeState
        }

        var doStrokeCompletion = false

        // 2. State transition detection (ErgometerJS parity)
        if (lastStrokeState != currentState) {
            if (currentState == CsafeConstants.STROKE_STATE_DRIVE) {
                // Catch detected -> Drive phase beginning
                strokeDriveStartMs = now
            } else if ((lastStrokeState == CsafeConstants.STROKE_STATE_DRIVE || lastStrokeState == CsafeConstants.STROKE_STATE_DWELL)
                && (currentState == CsafeConstants.STROKE_STATE_RECOVERY || currentState == CsafeConstants.STROKE_STATE_WAITING)
            ) {
                // Drive finished -> Transitioned to recovery!
                strokeDriveEndMs = now
                doStrokeCompletion = true
            }
            lastStrokeState = currentState
        }

        // 3. Low-resolution telemetry query (periodically or upon stroke finish)
        val shouldFetchTelemetry = doStrokeCompletion ||
            (lastTelemetryUpdateMs == 0L) ||
            (now - lastTelemetryUpdateMs >= TELEMETRY_REFRESH_INTERVAL_MS)

        var telemetry: ParsedTelemetry? = null
        if (shouldFetchTelemetry) {
            val telemCmd = CsafeProtocol.buildCombinedTelemetryCommand()
            val telemResponse = transport.executeCsafeCommand(telemCmd)
            if (telemResponse != null) {
                telemetry = parseCombinedResponse(telemResponse)
                lastTelemetryUpdateMs = now
            }
        }

        // 4. Handle stroke completion
        if (doStrokeCompletion) {
            val telem = telemetry ?: run {
                val telemCmd = CsafeProtocol.buildCombinedTelemetryCommand()
                transport.executeCsafeCommand(telemCmd)?.let { parseCombinedResponse(it) }
            }

            // Fetch force plot curve points (32-byte chunks from PM5)
            val forcePoints = fetchForcePlotData()

            // ErgometerJS criteria: curve.size >= 4 points
            if (forcePoints.size >= 4 && telem != null) {
                strokeIndex++
                val measuredDriveMs = (strokeDriveEndMs - strokeDriveStartMs).toInt().coerceIn(200, 2500)
                val recovMs = if (lastStrokeTimestamp > 0) {
                    (strokeDriveStartMs - lastStrokeTimestamp - measuredDriveMs).toInt().coerceIn(400, 6000)
                } else 1800
                lastStrokeTimestamp = strokeDriveStartMs

                // Concept2 PM5 samples force at ~5ms intervals (or 200 Hz)
                val estDriveFromPoints = forcePoints.size * 5
                val driveMs = if (measuredDriveMs in 300..1800) measuredDriveMs else estDriveFromPoints

                val stroke = Stroke(
                    n = strokeIndex,
                    ts = now,
                    tsMs = telem.workTimeMs,
                    watts = telem.watts,
                    spm = telem.spm,
                    driveMs = driveMs,
                    recovMs = recovMs,
                    drag = telem.dragFactor.coerceAtLeast(100),
                    forceMap = forcePoints,
                    heartRate = telem.heartRate,
                    distanceMeters = telem.workDistanceMeters
                )

                currentSession?.strokes?.add(stroke)
                currentSession?.totalMeters = stroke.distanceMeters
                currentSession?.dragFactor = stroke.drag

                _strokeFlow.emit(stroke)

                // Update UI live metrics with latest stroke curve
                _liveMetrics.value = _liveMetrics.value.copy(
                    lastForceCurve = forcePoints,
                    strokeCount = strokeIndex
                )
            }
        }

        // 5. Update real-time live display metrics
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
