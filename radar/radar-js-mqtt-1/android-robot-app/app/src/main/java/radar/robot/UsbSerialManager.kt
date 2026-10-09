package radar.robot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Discovers the OTG USB serial adapter (Arduino / CH340 / CP210x / FTDI / native CDC),
 * opens it at 115200 8N1 and streams the bytes to [onData].
 */
class UsbSerialManager(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onData: (ByteArray) -> Unit
) {

    companion object {
        const val ACTION_USB_PERMISSION = "radar.robot.USB_PERMISSION"
    }

    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private var permissionPending = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            permissionPending = false
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            try { context?.unregisterReceiver(this) } catch (e: Exception) {}
            if (granted) {
                onStatus("USB permission granted")
                open()
            } else {
                onStatus("USB permission denied")
            }
        }
    }

    fun onDeviceAttached(dev: UsbDevice) {
        requestAndOpen(dev)
    }

    fun start() {
        val dev = manager.deviceList.values.firstOrNull()
        if (dev != null) requestAndOpen(dev)
        else onStatus("no USB device attached")
    }

    private fun requestAndOpen(dev: UsbDevice) {
        if (!manager.hasPermission(dev)) {
            if (permissionPending) return
            permissionPending = true
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            ContextCompat.registerReceiver(
                context, permissionReceiver, IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            manager.requestPermission(dev, pi)
        } else {
            open()
        }
    }

    fun open() {
        try {
            val drivers: List<UsbSerialDriver> = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            val drv = drivers.firstOrNull()
                ?: run { onStatus("no serial driver found"); return }
            val connection = manager.openDevice(drv.device)
                ?: run { onStatus("openDevice failed"); return }
            val p = drv.ports[0]
            p.open(connection)
            p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            p.dtr = true
            p.rts = true
            port = p
            running = true
            onStatus("USB connected: ${drv.device.deviceName}")
            thread = Thread { readLoop(p) }.apply { start() }
        } catch (e: Exception) {
            onStatus("USB open error: ${e.message}")
        }
    }

    private fun readLoop(p: UsbSerialPort) {
        val buf = ByteArray(256)
        while (running) {
            try {
                val samples = p.read(buf, 1000)
                if (samples > 0) {
                    val arr = if (samples == buf.size) buf else buf.copyOf(samples)
                    onData(arr)
                }
            } catch (e: Exception) {
                if (running) onStatus("USB read error: ${e.message}")
                break
            }
        }
    }

    fun write(text: String) {
        val p = port ?: return
        try { p.write(text.toByteArray(), 1000) } catch (e: Exception) {}
    }

    fun stop() {
        running = false
        try { thread?.interrupt() } catch (e: Exception) {}
        try { port?.close() } catch (e: Exception) {}
        port = null
    }
}