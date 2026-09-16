package com.concept2.strokelogger.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Manages physical USB connection to the Concept2 PM3/PM4/PM5 monitor
 * via Android's USB Host API (android.hardware.usb.UsbManager).
 */
class UsbTransport(private val context: Context) {

    companion object {
        private const val TAG = "UsbTransport"
        const val ACTION_USB_PERMISSION = "com.concept2.strokelogger.USB_PERMISSION"
        private const val DEFAULT_TIMEOUT_MS = 250
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    private val ioMutex = Mutex()

    /** Returns true if an active USB connection is open to the PM5. */
    val isConnected: Boolean
        get() = usbConnection != null && endpointIn != null && endpointOut != null

    /** Current device name / product name */
    val deviceName: String
        get() = usbDevice?.productName ?: "Concept2 PM5"

    /**
     * Scans for an attached Concept2 PM3/PM4/PM5 monitor.
     * @return Found UsbDevice or null if none attached.
     */
    fun findDevice(): UsbDevice? {
        val deviceList = usbManager.deviceList
        for ((_, device) in deviceList) {
            if (device.vendorId == CsafeConstants.CONCEPT2_VENDOR_ID) {
                Log.i(TAG, "Found Concept2 monitor: VID=${device.vendorId}, PID=${device.productId}")
                return device
            }
        }
        return null
    }

    /**
     * Checks if permission is granted for the device; if not, requests permission from the user.
     */
    fun requestPermission(device: UsbDevice) {
        if (!usbManager.hasPermission(device)) {
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    /**
     * Opens connection to the Concept2 monitor and claims Interface 0.
     * @return true if successfully connected and endpoints acquired.
     */
    suspend fun connect(device: UsbDevice): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            disconnect()

            if (!usbManager.hasPermission(device)) {
                Log.w(TAG, "Missing permission for device ${device.deviceName}")
                return@withContext false
            }

            val connection = usbManager.openDevice(device) ?: run {
                Log.e(TAG, "Failed to open UsbDeviceConnection")
                return@withContext false
            }

            // Concept2 monitors use Interface 0 for HID/CSAFE communication
            if (device.interfaceCount == 0) {
                Log.e(TAG, "Device has no interfaces")
                connection.close()
                return@withContext false
            }

            val iface = device.getInterface(0)
            if (!connection.claimInterface(iface, true)) {
                Log.e(TAG, "Could not claim interface 0")
                connection.close()
                return@withContext false
            }

            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null

            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    epIn = ep
                } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    epOut = ep
                }
            }

            if (epIn == null || epOut == null) {
                Log.e(TAG, "Could not locate both IN and OUT endpoints")
                connection.releaseInterface(iface)
                connection.close()
                return@withContext false
            }

            usbDevice = device
            usbConnection = connection
            usbInterface = iface
            endpointIn = epIn
            endpointOut = epOut

            Log.i(TAG, "Successfully connected to Concept2: ${device.productName} (PID ${device.productId})")
            return@withContext true
        }
    }

    /**
     * Sends a CSAFE command to the PM5 and awaits the unescaped response payload.
     *
     * @param commandBytes Unescaped CSAFE command payload.
     * @param timeoutMs Maximum milliseconds to wait for USB transfer.
     * @return Unescaped response payload, or null if communication failed.
     */
    suspend fun executeCsafeCommand(
        commandBytes: ByteArray,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): ByteArray? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val conn = usbConnection ?: return@withContext null
            val epOut = endpointOut ?: return@withContext null
            val epIn = endpointIn ?: return@withContext null

            val packedReport = CsafeProtocol.packFrame(commandBytes)

            // Send 121-byte report to PM5 OUT endpoint
            val bytesWritten = conn.bulkTransfer(epOut, packedReport, packedReport.size, timeoutMs)
            if (bytesWritten < 0) {
                Log.w(TAG, "Failed writing to USB OUT endpoint: $bytesWritten")
                return@withContext null
            }

            // Read response from PM5 IN endpoint
            val readBuf = ByteArray(CsafeConstants.WRITE_BUF_SIZE)
            val bytesRead = conn.bulkTransfer(epIn, readBuf, readBuf.size, timeoutMs)
            if (bytesRead <= 0) {
                return@withContext null
            }

            return@withContext CsafeProtocol.unpackFrame(readBuf, bytesRead)
        }
    }

    /**
     * Closes the USB connection and releases claimed interfaces.
     */
    fun disconnect() {
        try {
            usbInterface?.let { usbConnection?.releaseInterface(it) }
            usbConnection?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing USB connection", e)
        } finally {
            usbDevice = null
            usbConnection = null
            usbInterface = null
            endpointIn = null
            endpointOut = null
        }
    }
}
