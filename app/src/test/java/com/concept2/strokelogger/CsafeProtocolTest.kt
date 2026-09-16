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

    @Test
    fun testPackFrameErgometerJsParity() {
        // ErgometerJS requires Report ID 0x02 and 121 bytes buffer
        val strokeCmd = CsafeProtocol.buildStrokeStateCommand()
        val packed = CsafeProtocol.packFrame(strokeCmd, forceLongReport = true)
        assertEquals(121, packed.size)
        assertEquals(0x02.toByte(), packed[0])
        assertEquals(CsafeConstants.FRAME_START_BYTE, packed[1]) // 0xF1
        assertEquals(CsafeConstants.CSAFE_PM_WRAPPER, packed[2]) // 0x1A
        assertEquals(1.toByte(), packed[3])
        assertEquals(CsafeConstants.CSAFE_PM_GET_STROKESTATE, packed[4]) // 0xBF
        assertEquals(0xA4.toByte(), packed[5]) // XOR checksum (1A ^ 01 ^ BF = A4)
        assertEquals(CsafeConstants.FRAME_END_BYTE, packed[6]) // 0xF2
        // All trailing bytes up to index 120 must be zero
        for (i in 7 until 121) {
            assertEquals(0.toByte(), packed[i])
        }
    }

    @Test
    fun testParseStrokeStateResponse() {
        // [status: 0x81, 0x1A, wrapLen: 0x03, 0xBF, subLen: 0x01, state: 0x02]
        val payload = byteArrayOf(0x81.toByte(), 0x1A, 0x03, 0xBF.toByte(), 0x01, 0x02)
        val state = CsafeProtocol.parseStrokeStateResponse(payload)
        assertEquals(CsafeConstants.STROKE_STATE_DRIVE, state)
    }

    @Test
    fun testParseForcePlotResponse() {
        // 2 force points: 150 (0x0096) and 350 (0x015E)
        // bytesReturned = 4
        // subLen = 5
        // wrapLen = 7
        val payload = byteArrayOf(
            0x81.toByte(), // status
            0x1A,          // wrapper
            0x07,          // wrapLen
            0x6B,          // detailCmd (0x6B)
            0x05,          // subLen
            0x04,          // bytesReturned
            0x96.toByte(), 0x00, // pt 1: 150
            0x5E.toByte(), 0x01  // pt 2: 350
        )
        val points = CsafeProtocol.parseForcePlotResponse(payload)
        assertEquals(2, points.size)
        assertEquals(150, points[0])
        assertEquals(350, points[1])
    }

    @Test
    fun testUnpackRealPm5ResponseWithoutOOM() {
        // Realistic PM5 response: [ReportId: 0x02, Start: 0xF1, Status: 0x81, Wrapper: 0x1A, 0x03, 0xBF, 0x01, 0x02, Checksum: 0x24, End: 0xF2, 0x00...]
        val raw = ByteArray(121)
        raw[0] = 0x02.toByte()
        raw[1] = CsafeConstants.FRAME_START_BYTE // 0xF1
        raw[2] = 0x81.toByte()                  // Status
        raw[3] = 0x1A                           // Wrapper
        raw[4] = 0x03                           // Length
        raw[5] = CsafeConstants.CSAFE_PM_GET_STROKESTATE // 0xBF
        raw[6] = 0x01
        raw[7] = 0x02                           // State = DRIVE
        raw[8] = 0x24                           // Checksum
        raw[9] = CsafeConstants.FRAME_END_BYTE   // 0xF2

        val unpacked = CsafeProtocol.unpackFrame(raw, raw.size)
        assertNotNull("Unpacked frame must not be null", unpacked)
        assertEquals(6, unpacked!!.size)
        assertEquals(0x81.toByte(), unpacked[0])
        assertEquals(0x1A.toByte(), unpacked[1])
        assertEquals(0x03.toByte(), unpacked[2])
        assertEquals(0xBF.toByte(), unpacked[3])
        assertEquals(0x01.toByte(), unpacked[4])
        assertEquals(0x02.toByte(), unpacked[5])

        // Verify that parseStrokeStateResponse correctly extracts DRIVE from this unpacked payload
        val state = CsafeProtocol.parseStrokeStateResponse(unpacked)
        assertEquals(CsafeConstants.STROKE_STATE_DRIVE, state)
    }

    @Test
    fun testUnpackFrameMemorySafetyOnCorruptInput() {
        // A corrupted frame where length is specified but no end byte exists
        val corrupt = ByteArray(64) { 0x55.toByte() }
        corrupt[0] = CsafeConstants.FRAME_START_BYTE
        val res = CsafeProtocol.unpackFrame(corrupt, corrupt.size)
        assertEquals(null, res)
    }
}
