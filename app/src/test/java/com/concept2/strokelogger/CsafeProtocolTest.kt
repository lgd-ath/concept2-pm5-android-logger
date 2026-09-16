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

        assertEquals(CsafeConstants.WRITE_BUF_SIZE, packed.size)
        assertEquals(CsafeConstants.REPORT_TYPE, packed[0])       // 0x02
        assertEquals(CsafeConstants.FRAME_START_BYTE, packed[1])  // 0xF1
        assertEquals(CsafeConstants.CSAFE_GETPOWER_CMD, packed[2]) // 0xB4
        assertEquals(CsafeConstants.CSAFE_GETPOWER_CMD, packed[3]) // Checksum = 0xB4
        assertEquals(CsafeConstants.FRAME_END_BYTE, packed[4])    // 0xF2
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
        assertEquals(4, strokeCmd.size)
        assertEquals(CsafeConstants.CSAFE_PM_WRAPPER, strokeCmd[0])
        assertEquals(CsafeConstants.CSAFE_PM_GET_STROKESTATE, strokeCmd[2])

        val forceCmd = CsafeProtocol.buildForcePlotCommand(32)
        assertEquals(4, forceCmd.size)
        assertEquals(CsafeConstants.CSAFE_PM_WRAPPER, forceCmd[0])
        assertEquals(CsafeConstants.CSAFE_PM_GET_FORCEPLOTDATA, forceCmd[2])
        assertEquals(32.toByte(), forceCmd[3])

        val combinedCmd = CsafeProtocol.buildCombinedTelemetryCommand()
        assertTrue(combinedCmd.isNotEmpty())
    }
}
