package com.ultronai.productarglasses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ultronai.productarglasses.camera.CameraController
import com.ultronai.productarglasses.databinding.ActivityMainBinding
import com.ultronai.productarglasses.network.MjpegServer
import kotlinx.coroutines.launch

class MainActivity : BaseMirrorActivity<ActivityMainBinding>() {

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val SERVER_PORT = 8080
        private const val TARGET_FPS = 30

        // Exposure compensation: positive = brighter, negative = darker
        // Typical range: -12 to +12 (each step ~0.33 EV)
        private const val EXPOSURE_COMPENSATION = 8
    }

    private var cameraController: CameraController? = null
    private var mjpegServer: MjpegServer? = null

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var lastFrameTime = 0L
    private val frameIntervalMs = 1000 / TARGET_FPS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBindingPair.updateView {
            tvTitle.text = "Product AR"
            tvStatus.text = "Status: Initializing..."
            tvIpAddress.text = "IP: ${getDeviceIpAddress()}:$SERVER_PORT"
        }

        setupTempleActions()
        checkPermissions()
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        } else {
            startCameraAndServer()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCameraAndServer()
        } else {
            mBindingPair.updateView {
                tvStatus.text = "Status: Camera permission denied"
            }
        }
    }

    private fun startCameraAndServer() {
        // Start MJPEG server
        mjpegServer = MjpegServer(
            port = SERVER_PORT,
            onClientConnected = { count ->
                runOnUiThread {
                    mBindingPair.updateView {
                        tvStatus.text = if (count > 0) "Status: $count client(s)" else "Status: Waiting..."
                        tvStatus.setTextColor(if (count > 0) 0xFF00FF00.toInt() else 0xFFFFFF00.toInt())
                    }
                }
            }
        )
        mjpegServer?.start()

        // Start camera
        cameraController = CameraController(
            context = this,
            onFrameAvailable = { jpeg, width, height ->
                val now = System.currentTimeMillis()
                if (now - lastFrameTime >= frameIntervalMs) {
                    lastFrameTime = now
                    mjpegServer?.sendFrame(jpeg)
                    updateFps()
                }
            },
            onError = { error ->
                mBindingPair.updateView {
                    tvStatus.text = "Error: $error"
                }
            }
        )
        cameraController?.start()

        mBindingPair.updateView {
            tvStatus.text = "Status: Waiting..."
        }
    }

    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            val fps = frameCount
            frameCount = 0
            lastFpsTime = now
            runOnUiThread {
                mBindingPair.updateView {
                    tvFps.text = "FPS: $fps"
                }
            }
        }
    }

    private fun getDeviceIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wifiManager.connectionInfo.ipAddress
        return "${ipInt and 0xFF}.${ipInt shr 8 and 0xFF}.${ipInt shr 16 and 0xFF}.${ipInt shr 24 and 0xFF}"
    }

    private fun setupTempleActions() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect { action ->
                    when (action) {
                        is TempleAction.DoubleClick -> finish()
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraController?.stop()
        mjpegServer?.stop()
    }
}