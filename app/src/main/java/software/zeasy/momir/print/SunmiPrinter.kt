package software.zeasy.momir.print

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import woyou.aidlservice.jiuiv5.ICallback
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

sealed class PrintResult {
    object Success : PrintResult()
    data class Failure(val message: String, val code: Int = -1) : PrintResult()
}

/**
 * Talks to the Sunmi built-in printer over its AIDL service.
 *
 * The connection is held open for the life of the activity. Binding costs a
 * couple of hundred milliseconds, and a Momir game is a lot of small prints in
 * quick succession - rebinding per slip would be felt.
 */
class SunmiPrinter(private val context: Context) {

    @Volatile
    private var service: woyou.aidlservice.jiuiv5.IWoyouService? = null
    private val binding = AtomicBoolean(false)
    private var pendingConnect: CancellableContinuation<Boolean>? = null

    val isConnected: Boolean get() = service != null

    /**
     * Told whenever the binding comes or goes, on the main thread.
     *
     * The service can die under a running app - it is a separate process, and on
     * a V2 it is restarted by anything that touches the printer from outside.
     * Until something listened to this, the first anyone knew of it was a press
     * that produced no paper.
     */
    var onStateChanged: ((connected: Boolean) -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = woyou.aidlservice.jiuiv5.IWoyouService.Stub.asInterface(binder)
            binding.set(false)
            Log.i(TAG, "Printer service connected")
            pendingConnect?.let { if (it.isActive) it.resume(true) }
            pendingConnect = null
            onStateChanged?.invoke(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Printer service disconnected")
            service = null
            binding.set(false)
            onStateChanged?.invoke(false)
        }
    }

    suspend fun connect(timeoutMs: Long = 8_000): Boolean {
        service?.let { return true }
        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    pendingConnect = continuation
                    val intent = Intent().apply {
                        `package` = SERVICE_PACKAGE
                        action = SERVICE_ACTION
                    }
                    val started = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                    if (!started) {
                        pendingConnect = null
                        if (continuation.isActive) continuation.resume(false)
                    } else {
                        binding.set(true)
                    }
                    continuation.invokeOnCancellation { pendingConnect = null }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Timed out binding to $SERVICE_PACKAGE")
            pendingConnect = null
            false
        }
    }

    fun disconnect() {
        if (service != null || binding.get()) {
            runCatching { context.unbindService(connection) }
        }
        service = null
    }

    /**
     * Prints one slip and feeds it clear of the tear bar.
     *
     * Initialise, image and feed all go out as a single sendRAWData call. The
     * payload is around 30 KB, nowhere near the 1 MB Binder ceiling, and one
     * transaction means the printer cannot interleave anything between the
     * raster and its feed.
     */
    suspend fun print(raster: Raster, feedDots: Int): PrintResult {
        val printer = service ?: return PrintResult.Failure("Printer service not connected")

        val payload = ByteArray(0) +
            EscPos.initialise() +
            EscPos.align(0) +
            EscPos.raster(raster.bytes, raster.height) +
            EscPos.feedDots(feedDots)

        return try {
            printer.printerInit(null)
            awaitCallback { callback -> printer.sendRAWData(payload, callback) }
        } catch (e: RemoteException) {
            Log.e(TAG, "Print failed", e)
            PrintResult.Failure(e.message ?: "Remote exception")
        }
    }

    /**
     * Reads back the service's own identity strings.
     *
     * This doubles as a check that our trimmed AIDL lines up with the firmware:
     * transaction codes come from declaration order, so if the mapping were off
     * these would throw or return nonsense rather than a version and a serial.
     */
    fun diagnostics(): Map<String, String> {
        val printer = service ?: return mapOf("state" to "not connected")
        return try {
            mapOf(
                "service" to (printer.serviceVersion ?: "?"),
                "model" to (printer.printerModal ?: "?"),
                "firmware" to (printer.printerVersion ?: "?"),
                "serial" to (printer.printerSerialNo ?: "?"),
                "status" to printer.firmwareStatus.toString(),
            )
        } catch (e: RemoteException) {
            mapOf("error" to (e.message ?: "remote exception"))
        }
    }

    private suspend fun awaitCallback(
        timeoutMs: Long = 30_000,
        block: (ICallback) -> Unit,
    ): PrintResult = try {
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val settled = AtomicBoolean(false)
                fun settle(result: PrintResult) {
                    if (settled.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(result)
                    }
                }

                val callback = object : ICallback.Stub() {
                    override fun onRunResult(isSuccess: Boolean) {
                        if (isSuccess) settle(PrintResult.Success)
                        else settle(PrintResult.Failure("Printer reported failure"))
                    }

                    override fun onReturnString(result: String?) = settle(PrintResult.Success)

                    override fun onRaiseException(code: Int, msg: String?) =
                        settle(PrintResult.Failure(msg ?: describe(code), code))

                    override fun onPrintResult(code: Int, msg: String?) {
                        if (code == 0) settle(PrintResult.Success)
                        else settle(PrintResult.Failure(msg ?: describe(code), code))
                    }
                }

                try {
                    block(callback)
                } catch (e: RemoteException) {
                    settle(PrintResult.Failure(e.message ?: "Remote exception"))
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        PrintResult.Failure("Printer did not answer within ${timeoutMs / 1000}s")
    }

    private fun describe(code: Int) = when (code) {
        1 -> "Printer is out of paper"
        2 -> "Print head is overheating"
        3 -> "Printer cover is open"
        else -> "Printer error $code"
    }

    companion object {
        private const val TAG = "SunmiPrinter"
        private const val SERVICE_PACKAGE = "woyou.aidlservice.jiuiv5"
        private const val SERVICE_ACTION = "woyou.aidlservice.jiuiv5.IWoyouService"
    }
}
