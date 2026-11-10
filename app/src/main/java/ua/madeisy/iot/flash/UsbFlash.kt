package ua.madeisy.iot.flash

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/** Порт usb-serial-for-android як [LoaderPort]. */
class UsbLoaderPort(val port: UsbSerialPort) : LoaderPort {
    private val buf = ByteArray(16 * 1024)
    override fun write(data: ByteArray) = port.write(data, 3000)
    override fun read(timeoutMs: Int): ByteArray {
        val n = try { port.read(buf, timeoutMs.coerceAtLeast(5)) } catch (e: Exception) { throw FlashException("USB від'єднано") }
        return if (n > 0) buf.copyOf(n) else ByteArray(0)
    }
    override fun setDtr(v: Boolean) { runCatching { port.setDTR(v) } }
    override fun setRts(v: Boolean) { runCatching { port.setRTS(v) } }
    override fun setBaud(baud: Int) = port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    fun close() { runCatching { port.close() } }
}

object UsbFlash {
    private const val ACTION_PERMISSION = "ua.madeisy.iot.USB_PERMISSION"
    const val ESPRESSIF_VID = 0x303A

    /** Вбудований USB ESP32-C3/S3/S2 — драйвер CDC-ACM. */
    private val espProber = UsbSerialProber(ProbeTable().apply {
        addProduct(ESPRESSIF_VID, 0x1001, CdcAcmSerialDriver::class.java)  // USB-Serial-JTAG (C3, S3)
        addProduct(ESPRESSIF_VID, 0x0002, CdcAcmSerialDriver::class.java)  // ROM CDC ESP32-S2
        addProduct(ESPRESSIF_VID, 0x80C2, CdcAcmSerialDriver::class.java)  // Lolin S2 mini (TinyUSB)
        addProduct(ESPRESSIF_VID, 0x0003, CdcAcmSerialDriver::class.java)  // ROM CDC ESP32-S3
    })

    fun drivers(ctx: Context): List<UsbSerialDriver> {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val found = UsbSerialProber.getDefaultProber().findAllDrivers(mgr).toMutableList()
        espProber.findAllDrivers(mgr).forEach { d -> if (found.none { it.device.deviceId == d.device.deviceId }) found += d }
        return found
    }

    fun describe(d: UsbSerialDriver): String {
        val dev = d.device
        val chip = when {
            dev.vendorId == ESPRESSIF_VID -> "вбудований USB ESP"
            dev.vendorId == 0x1A86 -> "CH340/CH9102"
            dev.vendorId == 0x10C4 -> "CP210x"
            dev.vendorId == 0x0403 -> "FTDI"
            else -> d.javaClass.simpleName.removeSuffix("SerialDriver")
        }
        val name = dev.productName?.takeIf { it.isNotBlank() } ?: "USB-пристрій"
        return "$name · $chip"
    }

    fun isUsbJtag(d: UsbSerialDriver) = d.device.vendorId == ESPRESSIF_VID

    suspend fun requestPermission(ctx: Context, device: UsbDevice): Boolean {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        if (mgr.hasPermission(device)) return true
        return withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        if (intent.action != ACTION_PERMISSION) return
                        runCatching { ctx.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(mgr.hasPermission(device))
                    }
                }
                ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags)
                mgr.requestPermission(device, pi)
                cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }
            }
        } ?: false
    }

    fun open(ctx: Context, d: UsbSerialDriver, baud: Int = 115200): UsbLoaderPort {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(d.device) ?: throw FlashException("Немає доступу до USB-пристрою")
        val p = d.ports.first()
        p.open(conn)
        p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        return UsbLoaderPort(p)
    }

    /**
     * Після скидання плати з вбудованим USB вона зникає з шини й з'являється знову —
     * шукаємо її повторно за VID/PID (VID той самий, PID може змінитись) і відкриваємо.
     */
    suspend fun reopen(ctx: Context, vendorId: Int, baud: Int = 115200, timeoutMs: Long = 8000): UsbLoaderPort? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val d = drivers(ctx).firstOrNull { it.device.vendorId == vendorId }
            if (d != null) {
                if (!requestPermission(ctx, d.device)) return null
                return runCatching { open(ctx, d, baud) }.getOrNull()
            }
            delay(500)
        }
        return null
    }

    /**
     * Передає Wi-Fi прошивці IoT Devices через той самий кабель
     * (команда «WIFI {json}», відповідь «IOTDEV-OK»). Повертає id плати або null.
     */
    suspend fun provisionWifi(port: UsbLoaderPort, ssid: String, pass: String, log: (String) -> Unit): String? =
        withContext(Dispatchers.IO) {
            var id: String? = null
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + 25_000
            var shown = 0
            var lastInfo = 0L
            var wifiSent = false
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (id == null && now - lastInfo > 1500) { port.write("\nINFO\n".toByteArray()); lastInfo = now }
                sb.append(String(port.read(200), Charsets.UTF_8))
                while (true) {
                    val nl = sb.indexOf("\n"); if (nl < 0) break
                    val line = sb.substring(0, nl).trim(); sb.delete(0, nl + 1)
                    when {
                        line.startsWith("IOTDEV {") -> {
                            id = runCatching { JSONObject(line.removePrefix("IOTDEV ")).getString("id") }.getOrNull()
                            if (id != null && !wifiSent) {
                                log("Плата відповіла: $id")
                                val cmd = "WIFI " + JSONObject().put("ssid", ssid).put("pass", pass).toString() + "\n"
                                port.write(cmd.toByteArray()); wifiSent = true
                            }
                        }
                        line.startsWith("IOTDEV-OK") -> return@withContext id
                        line.startsWith("IOTDEV-ERR") -> { log(line); return@withContext null }
                        // решту виводу плати показуємо в журналі — так видно, чи вона взагалі стартує
                        line.isNotBlank() && shown < 12 -> { shown++; log("плата: " + line.take(80)) }
                    }
                }
            }
            null
        }
}
