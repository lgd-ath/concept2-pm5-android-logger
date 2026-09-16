package com.concept2.strokelogger

import com.concept2.strokelogger.usb.CsafeConstants
import com.concept2.strokelogger.usb.CsafeProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for CSAFE protocol framing, byte stuffing, and checksum calculations.
 */
class CsafeProtocolTest {

    @Test
    fun testPackFrameBasic() {
        val cmd = byteArrayOf(CsafeConstants.CSAFE_GETPOWER_CMD)
        val packed = CsafeProtocol.packFrame(cmd)

        // Short command (5 framed bytes) defaults to REPORT_ID_SHORT (21 bytes)
        assertEquals(CsafeConstants.REPORT_SIZE_SHORT, packed.size)
        assertEquals(CsafeConstants.REPORT_ID_SHORT, packed[0])   // 0x01
        assertEquals(CsafeConstants.FRAME_START_BYTE, packed[1])  // 0xF1
        assertEquals(CsafeConstants.CSAFE_GETPOWER_CMD, packed[2]) // 0xB4
        assertEquals(CsafeConstants.CSAFE_GETPOWER_CMD, packed[3]) // Checksum = 0xB4
        assertEquals(CsafeConstants.FRAME_END_BYTE, packed[4])    // 0xF2
    }

    @Test
    fun testPackFrameDynamicReportSelection() {
        // Compound telemetry with expected response <= 63 bytes selects Report ID 0x04
        val telemetryCmd = CsafeProtocol.buildCombinedTelemetryCommand()
        val packedTelemetry = CsafeProtocol.packFrame(telemetryCmd, maxResponseBytes = 40)
        assertEquals(CsafeConstants.REPORT_SIZE_MEDIUM, packedTelemetry.size)
        assertEquals(CsafeConstants.REPORT_ID_MEDIUM, packedTelemetry[0]) // 0x04

        // Force plot query with expected response > 63 bytes selects Report ID 0x02
        val forcePlotCmd = CsafeProtocol.buildForcePlotCommand(32)
        val packedForcePlot = CsafeProtocol.packFrame(forcePlotCmd, maxResponseBytes = 100)
        assertEquals(CsafeConstants.REPORT_SIZE_LONG, packedForcePlot.size)
        assertEquals(CsafeConstants.REPORT_ID_LONG, packedForcePlot[0]) // 0x02
    }

    @Test
    fun testPackAndUnpackRoundtrip() {
        val payload = byteArrayOf(0x01, 0x02, 0x1A, 0x24, 0x55)
        val packed = CsafeProtocol.packFrame(payload)
        val unpacked = CsafeProtocol.unpackFrame(packed, packed.size)

        assertNotNull("Unpacked frame should not be null", unpacked)
        assertArrayEquals(payload, unpacked)
    }

    @Test
    fun testByteStuffing() {
        // Payload containing bytes that require stuffing (0xF0..0xF3)
        val payload = byteArrayOf(0x10, 0xF0.toByte(), 0xF1.toByte(), 0xF2.toByte(), 0xF3.toByte(), 0x20)
        val packed = CsafeProtocol.packFrame(payload)
        val unpacked = CsafeProtocol.unpackFrame(packed, packed.size)

        assertNotNull("De-stuffed frame should not be null", unpacked)
        assertArrayEquals("Payload with stuffed bytes should match exactly after roundtrip", payload, unpacked)
    }

    @Test
    fun testCommandBuilders() {
        val strokeCmd = CsafeProtocol.buildStrokeStateCommand()
        assertEquals(3, strokeCmd.size)
        assertEquals(CsafeConstants.CSAFE_PM_WRAPPER, strokeCmd[0])
        assertEquals(1.toByte(), strokeCmd[1]) // wrapper length
        assertEquals(CsafeConstants.CSAFE_PM_GET_STROKESTATE, strokeCmd[2])

        val forceCmd = CsafeProtocol.buildForcePlotCommand(32)
        assertEquals(5, forceCmd.size)
        assertEquals(CsafeConstants.CSAFE_PM_WRAPPER, forceCmd[0])
        assertEquals(3.toByte(), forceCmd[1]) // wrapper length
        assertEquals(CsafeConstants.CSAFE_PM_GET_FORCEPLOTDATA, forceCmd[2])
        assertEquals(1.toByte(), forceCmd[3]) // arg count
        assertEquals(32.toByte(), forceCmd[4]) // bytes requested

        val combinedCmd = CsafeProtocol.buildCombinedTelemetryCommand()
        assertEquals(9, combinedCmd.size)
        assertEquals(CsafeConstants.CSAFE_GETPOWER_CMD, combinedCmd[0])
        assertEquals(CsafeConstants.CSAFE_GETCADENCE_CMD, combinedCmd[1])
        assertEquals(CsafeConstants.CSAFE_GETHRCUR_CMD, combinedCmd[2])
        assertEquals(CsafeConstants.CSAFE_PM_WRAPPER, combinedCmd[3])
        assertEquals(4.toByte(), combinedCmd[4])
    }
}
