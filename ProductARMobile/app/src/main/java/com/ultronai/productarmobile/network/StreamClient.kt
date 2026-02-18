package com.ultronai.productarmobile.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class StreamClient(
    private val onFrameReceived: (Bitmap) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit,
    private val onFpsUpdate: (Int) -> Unit
) {
    companion object {
        private const val TAG = "StreamClient"
        private const val CONNECT_TIMEOUT = 5000
        private const val READ_TIMEOUT = 10000
        private const val FRAME_MAGIC = 0x46524D45 // "FRME"
        private const val MAX_FRAME_SIZE = 500 * 1024
    }

    private var socket: Socket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: PrintWriter? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isRunning = false

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()

    fun connect(ip: String, port: Int = 8080) {
        if (isRunning) return

        thread {
            try {
                Log.i(TAG, "Connecting to $ip:$port...")

                val sock = Socket()
                sock.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT)
                sock.soTimeout = READ_TIMEOUT
                sock.tcpNoDelay = true
                sock.receiveBufferSize = 512 * 1024

                socket = sock
                inputStream = DataInputStream(BufferedInputStream(sock.getInputStream(), 256 * 1024))
                outputStream = PrintWriter(BufferedWriter(OutputStreamWriter(sock.getOutputStream())), true)

                isRunning = true

                mainHandler.post {
                    onConnectionChanged(true)
                }

                Log.i(TAG, "Connected!")
                receiveFrames()

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                mainHandler.post {
                    onConnectionChanged(false)
                }
            }
        }
    }

    private fun receiveFrames() {
        val buffer = ByteArray(MAX_FRAME_SIZE)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        try {
            while (isRunning && socket?.isConnected == true) {
                // Read magic marker
                val magic = try {
                    inputStream?.readInt() ?: break
                } catch (e: Exception) {
                    Log.w(TAG, "Read error: ${e.message}")
                    break
                }

                if (magic != FRAME_MAGIC) {
                    Log.w(TAG, "Lost sync, skipping byte...")
                    continue
                }

                // Read frame length
                val length = inputStream?.readInt() ?: break

                if (length <= 0 || length > MAX_FRAME_SIZE) {
                    Log.w(TAG, "Invalid frame length: $length")
                    continue
                }

                // Read JPEG data
                inputStream?.readFully(buffer, 0, length)

                // Decode bitmap
                val bitmap = BitmapFactory.decodeByteArray(buffer, 0, length, options)
                if (bitmap != null) {
                    mainHandler.post {
                        onFrameReceived(bitmap)
                    }
                    updateFps()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving frames: ${e.message}")
        } finally {
            isRunning = false
            mainHandler.post {
                onConnectionChanged(false)
            }
        }
    }

    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            val fps = frameCount
            frameCount = 0
            lastFpsTime = now
            mainHandler.post {
                onFpsUpdate(fps)
            }
        }
    }

    fun sendResults(json: String) {
        if (!isRunning) return
        thread {
            try {
                outputStream?.println(json)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending results", e)
            }
        }
    }

    fun disconnect() {
        isRunning = false
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }

    fun isConnected(): Boolean = isRunning && socket?.isConnected == true
}