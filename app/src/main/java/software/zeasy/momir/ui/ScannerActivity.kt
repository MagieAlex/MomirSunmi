package software.zeasy.momir.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import software.zeasy.momir.R
import software.zeasy.momir.databinding.ActivityScannerBinding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scans the QR code off a slip this app printed.
 *
 * Deliberately the old android.hardware.Camera API. The V2 runs Android 7.1,
 * where Camera2's LEGACY hardware level gives you the old pipeline behind a
 * newer interface and a pile of extra state machine for nothing. Camera1's
 * preview callback hands over an NV21 buffer, which is exactly the plane ZXing
 * wants - no colour conversion, no allocation per frame.
 *
 * Nothing here touches the network. The decoded URL is looked up against the
 * scryfall_uri column of the corpus already on the device.
 */
class ScannerActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityScannerBinding
    private var camera: Camera? = null
    private var previewWidth = 0
    private var previewHeight = 0

    private val decoding = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val decoder = Executors.newSingleThreadExecutor()

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.CHARACTER_SET to "UTF-8",
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.preview.holder.addCallback(this)
        binding.closeButton.setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                openCamera(binding.preview.holder)
            } else {
                Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // ------------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            openCamera(holder)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) = releaseCamera()

    override fun onPause() {
        super.onPause()
        releaseCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        decoder.shutdownNow()
    }

    private fun openCamera(holder: SurfaceHolder) {
        if (camera != null) return
        try {
            val instance = Camera.open() ?: run {
                Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_LONG).show()
                finish()
                return
            }

            val parameters = instance.parameters
            val size = choosePreviewSize(parameters)
            parameters.setPreviewSize(size.width, size.height)
            previewWidth = size.width
            previewHeight = size.height

            if (parameters.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) {
                parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            }
            instance.parameters = parameters

            // The preview is rotated for the eye only. Decoding runs on the raw
            // sensor buffer, and ZXing finds the finder patterns at any angle.
            instance.setDisplayOrientation(90)
            instance.setPreviewDisplay(holder)
            instance.setPreviewCallback { data, _ -> onFrame(data) }
            instance.startPreview()
            camera = instance
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open camera", e)
            Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun choosePreviewSize(parameters: Camera.Parameters): Camera.Size {
        val sizes = parameters.supportedPreviewSizes ?: return parameters.previewSize
        // Small enough that a 2014-era CPU can binarise every frame, large enough
        // that a 25-module QR at arm's length still resolves.
        return sizes
            .filter { it.width in 480..1280 }
            .minByOrNull { kotlin.math.abs(it.width - 800) }
            ?: sizes.first()
    }

    private fun releaseCamera() {
        camera?.run {
            runCatching { setPreviewCallback(null) }
            runCatching { stopPreview() }
            runCatching { release() }
        }
        camera = null
    }

    private fun onFrame(data: ByteArray?) {
        if (data == null || finished.get()) return
        if (!decoding.compareAndSet(false, true)) return

        decoder.execute {
            try {
                val text = decode(data)
                if (text != null && finished.compareAndSet(false, true)) {
                    runOnUiThread { deliver(text) }
                }
            } finally {
                decoding.set(false)
            }
        }
    }

    private fun decode(data: ByteArray): String? = try {
        val source = PlanarYUVLuminanceSource(
            data, previewWidth, previewHeight,
            0, 0, previewWidth, previewHeight,
            false,
        )
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))?.text
    } catch (e: Exception) {
        // NotFoundException on most frames; that is the normal case, not an error.
        null
    } finally {
        reader.reset()
    }

    private fun deliver(text: String) {
        releaseCamera()
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT, text))
        finish()
    }

    companion object {
        private const val TAG = "ScannerActivity"
        private const val REQUEST_CAMERA = 1001
        const val EXTRA_RESULT = "scanned_text"
        const val REQUEST_SCAN = 2001
    }
}
