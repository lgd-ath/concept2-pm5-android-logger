package com.concept2.strokelogger.data.model

import kotlin.math.roundToInt

/**
 * Represents a single rowing stroke with complete telemetry and discrete force curve samples.
 * Schema conforms 100% to the Concept2 Stroke-by-Stroke Analyzer (`stroke-by-stroke-analyzer`).
 */
data class Stroke(
    val n: Int,                          // Stroke number (1-indexed)
    val ts: Long,                        // Epoch timestamp in milliseconds
    val tsMs: Long,                      // PM workout elapsed time in milliseconds
    val watts: Int,                      // Mechanical power in Watts (per coaching rule)
    val spm: Int,                        // Cadence in strokes per minute
    val driveMs: Int,                    // Drive phase duration in milliseconds
    val recovMs: Int,                    // Recovery phase duration in milliseconds
    val drag: Int,                       // Flywheel drag factor
    val forceMap: List<Int>,             // Raw discrete handle force samples (Newtons)
    val heartRate: Int = 0,              // Heart rate in bpm (0 if unmeasured)
    val distanceMeters: Double = 0.0,    // Cumulative workout distance in meters
    val spmDerived: Boolean = false,
    val block: Int = 1,
    val phase: Int = 1
) {
    /** Peak force exerted during the drive phase (Newtons). */
    val peakForce: Int = if (forceMap.isNotEmpty()) forceMap.maxOrNull() ?: 0 else 0

    /** Drive percentage (0..100) at which peak force occurred. */
    val peakPct: Int = if (forceMap.size > 1 && peakForce > 0) {
        val peakIndex = forceMap.indexOf(peakForce)
        ((peakIndex.toDouble() / (forceMap.size - 1)) * 100).roundToInt()
    } else 0

    /** Total mechanical impulse in Newton-seconds (N·s). */
    val impulse: Double = if (forceMap.isNotEmpty() && driveMs > 0) {
        val dt = driveMs.toDouble() / forceMap.size / 1000.0
        val sum = forceMap.sum().toDouble()
        (sum * dt * 100.0).roundToInt() / 100.0
    } else 0.0

    /** Average force exerted across the drive phase (Newtons). */
    val avgForce: Int = if (driveMs > 0 && impulse > 0) {
        (impulse / (driveMs.toDouble() / 1000.0)).roundToInt()
    } else 0

    /** Peak Rate of Force Development (RFD) in Newtons per second (N/s). */
    val rfd: Int = if (forceMap.size > 1 && peakForce > 0 && driveMs > 0) {
        val peakIndex = forceMap.indexOf(peakForce).coerceAtLeast(1)
        val timeToPeakSec = (peakIndex.toDouble() * driveMs.toDouble() / forceMap.size) / 1000.0
        if (timeToPeakSec > 0) (peakForce / timeToPeakSec).roundToInt() else 0
    } else 0

    /** Catch percentage point (where force crosses 10% of peak). */
    val catchPct: Int = if (forceMap.size > 1 && peakForce > 0) {
        val thresh = peakForce * 0.1
        val idx = forceMap.indexOfFirst { it > thresh }
        if (idx >= 0) ((idx.toDouble() / (forceMap.size - 1)) * 100).roundToInt() else 0
    } else 0

    /** Finish percentage point (where force falls below 10% of peak). */
    val finishPct: Int = if (forceMap.size > 1 && peakForce > 0) {
        val thresh = peakForce * 0.1
        val idx = forceMap.indexOfLast { it > thresh }
        if (idx >= 0) ((idx.toDouble() / (forceMap.size - 1)) * 100).roundToInt() else 100
    } else 100

    /** Asymmetry profile string (Early, Symmetric, Late). */
    val shape: String = when {
        peakPct < 45 -> "Early"
        peakPct > 55 -> "Late"
        else -> "Symmetric"
    }
}
