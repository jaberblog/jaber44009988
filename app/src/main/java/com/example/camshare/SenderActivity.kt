package com.example.camshare

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class SenderActivity : AppCompatActivity() {

    companion object {
        const val PORT = 8080
        private const val JPEG_QUALITY = 65
    }

    private lateinit var previewView: PreviewView
    private lateinit var tvAddress: TextView
    private lateinit var tvClients: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnSwitch: Button

    private var server: MjpegServer? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var lens = CameraSelector.LENS_FACING_BACK
    private val executor = Executors.newSingleThreadExecutor()

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = when {
                    o in 45..134 -> Surface.ROTATION_270
                    o in 135..224 -> Surface.ROTATION_180
                    o in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                analysis?.targetRotation = r
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sender)
        previewView = findViewById(R.id.previewView)
        tvAddress = findViewById(R.id.tvAddress)
        tvClients = findViewById(R.id.tvClients)
        btnToggle = findViewById(R.id.btnToggle)
        btnSwitch = findViewById(R.id.btnSwitch)

        btnToggle.setOnClickListener { if (server == null) startSharing() else stopSharing() }
        btnSwitch.setOnClickListener {
            lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
            bindCamera()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onStop() {
        super.onStop()
        orientationListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSharing()
        provider?.unbindAll()
        executor.shutdown()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val p = provider ?: return
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            ).build()

        val a = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        a.setAnalyzer(executor) { image -> analyze(image) }
        analysis = a

        val selector = CameraSelector.Builder().requireLensFacing(lens).build()
        try {
            p.unbindAll()
            p.bindToLifecycle(this, selector, preview, a)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "camera error", Toast.LENGTH_LONG).show()
        }
    }

    private fun analyze(image: ImageProxy) {
        try {
            val s = server
            // اگر کسی وصل نیست، انکود نکن (صرفه‌جویی در باتری)
            if (s == null || s.clientCount() == 0) return

            val bmp = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            val upright = if (rot != 0) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            } else bmp

            val bos = ByteArrayOutputStream(64 * 1024)
            upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
            s.publish(bos.toByteArray())
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun startSharing() {
        val s = MjpegServer(PORT)
        s.onClientsChanged = { n -> runOnUiThread { tvClients.text = getString(R.string.clients_fmt, n) } }
        s.onError = {
            runOnUiThread {
                Toast.makeText(this, R.string.port_error, Toast.LENGTH_LONG).show()
                stopSharing()
            }
        }
        s.start()
        server = s

        val ip = NetUtils.localIp()
        tvAddress.text = if (ip != null) getString(R.string.sender_address, ip, PORT) else getString(R.string.no_wifi)
        tvClients.text = getString(R.string.clients_fmt, 0)
        btnToggle.setText(R.string.sender_stop)
    }

    private fun stopSharing() {
        server?.stop()
        server = null
        if (!isFinishing && !isDestroyed) {
            tvAddress.setText(R.string.sender_idle)
            tvClients.text = ""
            btnToggle.setText(R.string.sender_start)
        }
    }
}
