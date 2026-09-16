package com.concept2.strokelogger.usb

/**
 * Concept2 PM3/PM4/PM5 USB and CSAFE protocol constants.
 *
 * References:
 * - Concept2 Communication Interface Definition (CSAFE Communications Interface)
 * - ErgometerJS by Tijmen van Gulik (Apache 2.0)
 * - Laurent's Coach stroke-by-stroke analyzer documentation
 */
object CsafeConstants {
    // USB Hardware Identifiers
    const val CONCEPT2_VENDOR_ID = 6052 // 0x17A4
    const val PRODUCT_ID_PM3 = 1
    const val PRODUCT_ID_PM4 = 2
    const val PRODUCT_ID_PM5 = 3

    // USB HID Report Parameters
    // Concept2 USB HID requires Report ID 0x01 (21 bytes) for short commands,
    // 0x04 (63 bytes) for medium compound telemetry, and 0x02 (121 bytes) for large transfers (force plots).
    const val REPORT_ID_SHORT: Byte = 0x01
    const val REPORT_ID_MEDIUM: Byte = 0x04
    const val REPORT_ID_LONG: Byte = 0x02

    const val REPORT_SIZE_SHORT = 21
    const val REPORT_SIZE_MEDIUM = 63
    const val REPORT_SIZE_LONG = 121

    // Backward-compatibility aliases
    const val REPORT_TYPE: Byte = REPORT_ID_LONG
    const val WRITE_BUF_SIZE = REPORT_SIZE_LONG
    const val USB_CSAFE_SIZE = 120

    // CSAFE Frame Protocol Bytes
    const val EXT_FRAME_START_BYTE: Byte = 0xF0.toByte()
    const val FRAME_START_BYTE: Byte = 0xF1.toByte()
    const val FRAME_END_BYTE: Byte = 0xF2.toByte()
    const val FRAME_STUFF_BYTE: Byte = 0xF3.toByte()

    // CSAFE Standard Commands
    const val CSAFE_GETSTATUS_CMD: Byte = 0x80.toByte()
    const val CSAFE_RESET_CMD: Byte = 0x81.toByte()
    const val CSAFE_GOINUSE_CMD: Byte = 0x85.toByte()
    const val CSAFE_GETCADENCE_CMD: Byte = 0xA7.toByte() // Stroke cadence (SPM)
    const val CSAFE_GETPOWER_CMD: Byte = 0xB4.toByte()   // Instantaneous power (Watts)
    const val CSAFE_GETCALORIES_CMD: Byte = 0xA3.toByte()
    const val CSAFE_GETHRCUR_CMD: Byte = 0xB0.toByte()   // Heart Rate (bpm)

    // Concept2 Proprietary Wrapper (0x1A: CSAFE_SETUSERCFG1_CMD)
    const val CSAFE_PM_WRAPPER: Byte = 0x1A.toByte()
    const val CSAFE_PM_GET_WORKTIME: Byte = 0xA0.toByte()      // Elapsed time in 10ms increments
    const val CSAFE_PM_GET_WORKDISTANCE: Byte = 0xA3.toByte()  // Distance in 0.1m increments
    const val CSAFE_PM_GET_FORCEPLOTDATA: Byte = 0x6B.toByte() // Force samples in Newtons (16-bit LE)
    const val CSAFE_PM_GET_STROKESTATE: Byte = 0xBF.toByte()   // Stroke phase
    const val CSAFE_PM_GET_DRAGFACTOR: Byte = 0xC1.toByte()    // Aerodynamic drag factor

    // PM Stroke States
    const val STROKE_STATE_WAITING = 0 // Waiting for wheel to reach min speed
    const val STROKE_STATE_CATCH = 1   // Catch (waiting for wheel to accelerate)
    const val STROKE_STATE_DRIVE = 2   // Driving
    const val STROKE_STATE_DWELL = 3   // Dwelling after drive
    const val STROKE_STATE_RECOVERY = 4 // Recovery
}
