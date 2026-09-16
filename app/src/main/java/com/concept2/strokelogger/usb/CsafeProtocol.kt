package com.concept2.strokelogger.usb

import java.io.ByteArrayOutputStream

/**
 * Handles CSAFE packet framing, byte stuffing, and XOR checksum calculation
 * according to Concept2 PM3/PM4/PM5 communications specifications.
 */
object CsafeProtocol {

    /**
     * Encapsulates raw CSAFE command bytes into a complete USB HID output report.
     * Selects the proper Concept2 HID Report ID and buffer length:
     * - Report ID 0x01 (21 bytes) for short commands (max response <= 21)
     * - Report ID 0x04 (63 bytes) for standard telemetry (max response <= 63)
     * - Report ID 0x02 (121 bytes) for large transfers like force plots
     *
     * @param commandBytes Array of raw CSAFE command bytes.
     * @param maxResponseBytes Expected maximum response size to guide report selection.
     * @return Padded byte array ready to transmit to PM5 OUT endpoint.
     */
    fun packFrame(commandBytes: ByteArray, maxResponseBytes: Int = 0): ByteArray {
        val frameStream = ByteArrayOutputStream()
        frameStream.write(CsafeConstants.FRAME_START_BYTE.toInt()) // 0xF1

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
        val chkVal = checksum and 0xFF
        if (chkVal in 0xF0..0xF3) {
            frameStream.write(CsafeConstants.FRAME_STUFF_BYTE.toInt())
            frameStream.write(chkVal and 0x03)
        } else {
            frameStream.write(chkVal)
        }

        frameStream.write(CsafeConstants.FRAME_END_BYTE.toInt()) // 0xF2

        val framed = frameStream.toByteArray()
        val totalLength = framed.size + 1 // +1 for Report ID byte
        val maxLen = maxOf(totalLength, maxResponseBytes)

        val (reportId, targetSize) = when {
            maxLen <= CsafeConstants.REPORT_SIZE_SHORT -> Pair(CsafeConstants.REPORT_ID_SHORT, CsafeConstants.REPORT_SIZE_SHORT)
            maxLen <= CsafeConstants.REPORT_SIZE_MEDIUM -> Pair(CsafeConstants.REPORT_ID_MEDIUM, CsafeConstants.REPORT_SIZE_MEDIUM)
            else -> Pair(CsafeConstants.REPORT_ID_LONG, CsafeConstants.REPORT_SIZE_LONG)
        }

        val out = ByteArray(targetSize)
        out[0] = reportId
        System.arraycopy(framed, 0, out, 1, framed.size)
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
    // CSAFE Command Builders (Standard Concept2 / PyRow protocol structure)
    // ────────────────────────────────────────────────────────────────────────

    /** Build stroke state query command (CSAFE_PM_GET_STROKESTATE). */
    fun buildStrokeStateCommand(): ByteArray {
        return byteArrayOf(
            CsafeConstants.CSAFE_PM_WRAPPER,
            0x01.toByte(), // wrapper data length = 1
            CsafeConstants.CSAFE_PM_GET_STROKESTATE
        )
    }

    /**
     * Build force plot data query command (CSAFE_PM_GET_FORCEPLOTDATA).
     * Concept2 format: [0x1A, 0x03, 0x6B, 0x01, bytesRequested]
     */
    fun buildForcePlotCommand(bytesRequested: Int = 32): ByteArray {
        return byteArrayOf(
            CsafeConstants.CSAFE_PM_WRAPPER,
            0x03.toByte(), // wrapper data length = 3
            CsafeConstants.CSAFE_PM_GET_FORCEPLOTDATA,
            0x01.toByte(), // 1 argument follows
            bytesRequested.toByte()
        )
    }

    /**
     * Builds a single high-efficiency compound telemetry frame querying:
     * - Instantaneous power in Watts (0xB4)
     * - Stroke cadence in SPM (0xA7)
     * - Heart rate (0xB0)
     * - PM Wrapper 0x1A with length 4:
     *     - Work time (0xA0)
     *     - Work distance (0xA3)
     *     - Stroke state (0xBF)
     *     - Drag factor (0xC1)
     *
     * Total command payload is 9 bytes, fits cleanly in Report ID 0x04 (63 bytes).
     * Completely free of invalid trailing zeroes or malformed subcommands.
     */
    fun buildCombinedTelemetryCommand(): ByteArray {
        return byteArrayOf(
            CsafeConstants.CSAFE_GETPOWER_CMD,
            CsafeConstants.CSAFE_GETCADENCE_CMD,
            CsafeConstants.CSAFE_GETHRCUR_CMD,
            CsafeConstants.CSAFE_PM_WRAPPER,
            0x04.toByte(), // wrapper byte count = 4 (A0, A3, BF, C1)
            CsafeConstants.CSAFE_PM_GET_WORKTIME,
            CsafeConstants.CSAFE_PM_GET_WORKDISTANCE,
            CsafeConstants.CSAFE_PM_GET_STROKESTATE,
            CsafeConstants.CSAFE_PM_GET_DRAGFACTOR
        )
    }
}
