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
        private const val POLLING_INTERVAL_MS = 60L // ~16.6 Hz polling (Concept2 PM5 safe pacing)
    }

    private var pollingJob: Job? = null

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

        pollingJob?.cancel()
        pollingJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Starting PM5 high-frequency polling loop")
            while (isActive && transport.isConnected) {
                try {
                    pollIteration()
                    delay(POLLING_INTERVAL_MS)
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
     * Single polling tick: queries state, detects drive -> recovery transitions,
     * and streams force curve data when drive finishes.
     */
    private suspend fun pollIteration() {
        // 1. Query combined telemetry & stroke state (expected response size ~35 bytes -> Report ID 0x04)
        val cmd = CsafeProtocol.buildCombinedTelemetryCommand()
        val response = transport.executeCsafeCommand(cmd, maxResponseBytes = 40) ?: return

        val parsed = parseCombinedResponse(response)
        val currentState = parsed.strokeState
        val now = System.currentTimeMillis()

        // 2. State transition handling
        if (lastStrokeState != currentState) {
            if (currentState == CsafeConstants.STROKE_STATE_DRIVE) {
                // Catch detected -> Drive phase beginning
                strokeDriveStartMs = now
            } else if ((lastStrokeState == CsafeConstants.STROKE_STATE_DRIVE || lastStrokeState == CsafeConstants.STROKE_STATE_DWELL)
                && currentState == CsafeConstants.STROKE_STATE_RECOVERY
            ) {
                // Drive finished -> Transitioned to recovery!
                strokeDriveEndMs = now
                val measuredDriveMs = (strokeDriveEndMs - strokeDriveStartMs).toInt().coerceIn(200, 2000)

                // 3. Immediately query the complete discrete force curve vector
                val forcePoints = fetchForcePlotData()

                // Only log valid strokes with meaningful force points (>3 points)
                if (forcePoints.size >= 4) {
                    strokeIndex++
                    val recovMs = if (lastStrokeTimestamp > 0) {
                        (strokeDriveStartMs - lastStrokeTimestamp - measuredDriveMs).toInt().coerceIn(400, 6000)
                    } else 1800
                    lastStrokeTimestamp = strokeDriveStartMs

                    val driveMs = if (forcePoints.isNotEmpty()) {
                        // Concept2 samples force plot at ~5ms intervals
                        val estDriveFromPoints = forcePoints.size * 5
                        if (measuredDriveMs in 300..1500) measuredDriveMs else estDriveFromPoints
                    } else measuredDriveMs

                    val stroke = Stroke(
                        n = strokeIndex,
                        ts = now,
                        tsMs = parsed.workTimeMs,
                        watts = parsed.watts,
                        spm = parsed.spm,
                        driveMs = driveMs,
                        recovMs = recovMs,
                        drag = parsed.dragFactor.coerceAtLeast(100),
                        forceMap = forcePoints,
                        heartRate = parsed.heartRate,
                        distanceMeters = parsed.workDistanceMeters
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
            lastStrokeState = currentState
        }

        // 4. Update real-time live metrics
        _liveMetrics.value = _liveMetrics.value.copy(
            watts = parsed.watts,
            spm = parsed.spm,
            heartRate = parsed.heartRate,
            distanceMeters = parsed.workDistanceMeters,
            elapsedSeconds = parsed.workTimeMs / 1000,
            dragFactor = parsed.dragFactor,
            strokeState = currentState
        )
    }

    /**
     * Queries PM5 repeatedly using CSAFE_PM_GET_FORCEPLOTDATA until all 16-bit
     * force samples of the completed drive phase are retrieved.
     */
    private suspend fun fetchForcePlotData(): List<Int> {
        val forceMap = mutableListOf<Int>()
        var attempts = 0
        val maxAttempts = 12 // A typical drive yields 30-70 points (2-5 packets)

        while (attempts < maxAttempts) {
            attempts++
            val cmd = CsafeProtocol.buildForcePlotCommand(32)
            val response = transport.executeCsafeCommand(cmd, maxResponseBytes = 100) ?: break

            val chunk = parseForcePlotResponse(response)
            if (chunk.isEmpty()) {
                break // No more points returned by PM5
            }
            forceMap.addAll(chunk)
            if (chunk.size < 16) {
                // If fewer than 16 points (32 bytes) returned, that was the final chunk
                break
            }
        }
        return forceMap
    }

    /**
     * Unpacks 16-bit little-endian force sample integers from PM5 force plot response.
     * Response payload structure:
     * [Status, WRAPPER(0x1A), wrapperLen, 0x6B, subcmdLen, bytesReturned, d0_lo, d0_hi, ...]
     */
    private fun parseForcePlotResponse(payload: ByteArray): List<Int> {
        val points = mutableListOf<Int>()
        var idx = 0
        while (idx < payload.size - 5) {
            val byteVal = payload[idx].toInt() and 0xFF
            if (byteVal == (CsafeConstants.CSAFE_PM_WRAPPER.toInt() and 0xFF)
                && (payload[idx + 2].toInt() and 0xFF) == (CsafeConstants.CSAFE_PM_GET_FORCEPLOTDATA.toInt() and 0xFF)
            ) {
                val bytesReturned = payload[idx + 4].toInt() and 0xFF
                var dataIdx = idx + 5
                val endData = (dataIdx + bytesReturned).coerceAtMost(payload.size)
                while (dataIdx + 1 < endData) {
                    val lo = payload[dataIdx].toInt() and 0xFF
                    val hi = payload[dataIdx + 1].toInt() and 0xFF
                    val forceVal = (hi shl 8) or lo
                    points.add(forceVal)
                    dataIdx += 2
                }
                break
            }
            idx++
        }
        return points
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
