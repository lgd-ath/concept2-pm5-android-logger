package com.concept2.strokelogger.usb

import java.io.ByteArrayOutputStream

/**
 * Handles CSAFE packet framing, byte stuffing, and XOR checksum calculation
 * according to Concept2 PM3/PM4/PM5 communications specifications.
 */
object CsafeProtocol {

    /**
     * Encapsulates raw CSAFE command bytes into a complete USB HID output report.
     *
     * Structure of the output report:
     * - Byte 0: Report ID (0x02)
     * - Byte 1: Start Frame Byte (0xF1)
     * - Bytes 2..N: Escaped command bytes (byte-stuffed if 0xF0..0xF3)
     * - Byte N+1: Checksum byte (XOR sum of unescaped command bytes)
     * - Byte N+2: End Frame Byte (0xF2)
     * - Remaining bytes up to 121: 0x00 padding
     *
     * @param commandBytes Array of raw CSAFE command bytes.
     * @return 121-byte array ready to transmit to PM5 OUT endpoint.
     */
    fun packFrame(commandBytes: ByteArray): ByteArray {
        val out = ByteArray(CsafeConstants.WRITE_BUF_SIZE)
        out[0] = CsafeConstants.REPORT_TYPE // 0x02

        val frameStream = ByteArrayOutputStream()
        frameStream.write(CsafeConstants.FRAME_START_BYTE.toInt())

        var checksum = 0
        for (b in commandBytes) {
            val byteVal = b.toInt() and 0xFF
            checksum = checksum xor byteVal

            // Byte stuffing: 0xF0..0xF3 are escaped as 0xF3 followed by (byte & 0x03)
            if (byteVal in 0xF0..0xF3) {
                frameStream.write(CsafeConstants.FRAME_STUFF_BYTE.toInt())
                frameStream.write(byteVal and 0x03)
            } else {
                frameStream.write(byteVal)
            }
        }

        // Checksum byte stuffing
        if (checksum in 0xF0..0xF3) {
            frameStream.write(CsafeConstants.FRAME_STUFF_BYTE.toInt())
            frameStream.write(checksum and 0x03)
        } else {
            frameStream.write(checksum)
        }

        frameStream.write(CsafeConstants.FRAME_END_BYTE.toInt())

        val frameArray = frameStream.toByteArray()
        System.arraycopy(frameArray, 0, out, 1, frameArray.size.coerceAtMost(CsafeConstants.USB_CSAFE_SIZE))
        return out
    }

    /**
     * Decodes an incoming USB HID input report from the PM5.
     * Strips Report ID, validates Start/End framing, de-stuffs escaped bytes,
     * and validates the XOR checksum.
     *
     * @param rawData Raw bytes read from PM5 IN endpoint.
     * @param length Number of bytes read.
     * @return De-stuffed command response payload, or null if invalid frame.
     */
    fun unpackFrame(rawData: ByteArray, length: Int): ByteArray? {
        if (length < 4) return null

        // Find Start byte (0xF1 or 0xF0)
        var startIndex = -1
        for (i in 0 until length) {
            if (rawData[i] == CsafeConstants.FRAME_START_BYTE || rawData[i] == CsafeConstants.EXT_FRAME_START_BYTE) {
                startIndex = i
                break
            }
        }
        if (startIndex == -1) return null

        // Find End byte (0xF2) backwards
        var endIndex = -1
        for (i in length - 1 downTo startIndex) {
            if (rawData[i] == CsafeConstants.FRAME_END_BYTE) {
                endIndex = i
                break
            }
        }
        if (endIndex == -1 || endIndex <= startIndex + 2) return null

        // De-stuff bytes between Start and End
        val unescaped = ByteArrayOutputStream()
        var i = startIndex + 1
        while (i < endIndex) {
            val b = rawData[i].toInt() and 0xFF
            if (b == (CsafeConstants.FRAME_STUFF_BYTE.toInt() and 0xFF) && i + 1 < endIndex) {
                val nextByte = rawData[i + 1].toInt() and 0x03
                unescaped.write(0xF0 or nextByte)
                i += 2
            } else {
                unescaped.write(b)
            }
        }

        val unescapedBytes = unescaped.toByteArray()
        if (unescapedBytes.isEmpty()) return null

        // The last byte is the transmitted checksum
        val receivedChecksum = unescapedBytes.last().toInt() and 0xFF
        val payload = unescapedBytes.copyOf(unescapedBytes.size - 1)

        // Compute XOR checksum of payload
        var computedChecksum = 0
        for (b in payload) {
            computedChecksum = computedChecksum xor (b.toInt() and 0xFF)
        }

        if (computedChecksum != receivedChecksum) {
            return null // Checksum mismatch
        }

        return payload
    }

    // ────────────────────────────────────────────────────────────────────────
    // CSAFE Command Builders
    // ────────────────────────────────────────────────────────────────────────

    /** Build stroke state query command (CSAFE_PM_GET_STROKESTATE). */
    fun buildStrokeStateCommand(): ByteArray {
        return byteArrayOf(
            CsafeConstants.CSAFE_PM_WRAPPER,
            0x01.toByte(),
            CsafeConstants.CSAFE_PM_GET_STROKESTATE,
            0x00.toByte()
        )
    }

    /** Build force plot data query command (CSAFE_PM_GET_FORCEPLOTDATA). */
    fun buildForcePlotCommand(bytesRequested: Int = 32): ByteArray {
        return byteArrayOf(
            CsafeConstants.CSAFE_PM_WRAPPER,
            0x02.toByte(),
            CsafeConstants.CSAFE_PM_GET_FORCEPLOTDATA,
            bytesRequested.toByte()
        )
    }

    /**
     * Builds a single high-efficiency compound telemetry frame querying:
     * - Stroke state (0xBF)
     * - Instantaneous power in Watts (0xB4)
     * - Stroke cadence in SPM (0xA7)
     * - Work time (0xA0)
     * - Work distance (0xA3)
     * - Heart rate (0xB0)
     * - Drag factor (0xC1)
     */
    fun buildCombinedTelemetryCommand(): ByteArray {
        val stream = ByteArrayOutputStream()
        // PM Stroke State
        stream.write(byteArrayOf(CsafeConstants.CSAFE_PM_WRAPPER, 0x01, CsafeConstants.CSAFE_PM_GET_STROKESTATE, 0x00))
        // Power (Watts)
        stream.write(CsafeConstants.CSAFE_GETPOWER_CMD.toInt())
        // Cadence (SPM)
        stream.write(CsafeConstants.CSAFE_GETCADENCE_CMD.toInt())
        // Heart Rate
        stream.write(CsafeConstants.CSAFE_GETHRCUR_CMD.toInt())
        // Work Time
        stream.write(byteArrayOf(CsafeConstants.CSAFE_PM_WRAPPER, 0x01, CsafeConstants.CSAFE_PM_GET_WORKTIME, 0x00))
        // Work Distance
        stream.write(byteArrayOf(CsafeConstants.CSAFE_PM_WRAPPER, 0x01, CsafeConstants.CSAFE_PM_GET_WORKDISTANCE, 0x00))
        // Drag Factor
        stream.write(byteArrayOf(CsafeConstants.CSAFE_PM_WRAPPER, 0x01, CsafeConstants.CSAFE_PM_GET_DRAGFACTOR, 0x00))
        return stream.toByteArray()
    }
}
