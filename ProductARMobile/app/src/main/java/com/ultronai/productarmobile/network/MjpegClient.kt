package com.ultronai.productarmobile.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MjpegClient(
    private val onFrameReceived: (Bitmap) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit,
    private val onFpsUpdate: (Int) -> Unit,
    private val onError: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "MjpegClient"
        private const val CONNECT_TIMEOUT = 5000
        private const val READ_TIMEOUT = 10000
    }

    private var connection: HttpURLConnection? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isRunning = false

    private var connectThread: Thread? = null
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()

    fun connect(ip: String, port: Int = 8080) {
        if (isRunning) return
        connectThread?.let {
            if (it.isAlive) return
        }

        connectThread = thread {
            try {
                Log.i(TAG, "Connecting to $ip:$port...")

                val url = URL("http://$ip:$port/stream")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    requestMethod = "GET"
                    setRequestProperty("Connection", "keep-alive")
                }

                connection?.connect()

                if (connection?.responseCode != 200) {
                    throw Exception("HTTP error: ${connection?.responseCode}")
                }

                isRunning = true
                mainHandler.post { onConnectionChanged(true) }

                Log.i(TAG, "Connected!")

                val inputStream = BufferedInputStream(connection?.inputStream, 256 * 1024)
                readMjpegStream(inputStream)

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                val msg = e.message ?: "Unknown error"
                mainHandler.post {
                    onConnectionChanged(false)
                    onError?.invoke(msg)
                }
            }
        }
    }

    private fun readMjpegStream(input: BufferedInputStream) {
        val buffer = ByteArray(4096)
        val jpegBuffer = ByteArrayOutputStream()
        var inImage = false
        var contentLength = -1

        try {
            while (isRunning) {
                // Read line by line until we find Content-Length or JPEG start
                val line = readLine(input)

                if (line == null) {
                    Log.w(TAG, "Stream EOF reached")
                    break
                }

                when {
                    line.startsWith("Content-Length:") -> {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
                    }
                    line.isEmpty() && contentLength > 0 -> {
                        // Empty line after headers, read JPEG data
                        jpegBuffer.reset()
                        var remaining = contentLength

                        while (remaining > 0) {
                            val toRead = minOf(remaining, buffer.size)
                            val read = input.read(buffer, 0, toRead)
                            if (read == -1) break
                            jpegBuffer.write(buffer, 0, read)
                            remaining -= read
                        }

                        if (jpegBuffer.size() > 0) {
                            val jpeg = jpegBuffer.toByteArray()
                            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                            if (bitmap != null) {
                                mainHandler.post { onFrameReceived(bitmap) }
                                updateFps()
                            }
                        }
                        contentLength = -1
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stream error: ${e.message}")
        } finally {
            isRunning = false
            mainHandler.post { onConnectionChanged(false) }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        var c: Int

        while (true) {
            c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\r'.code) {
                input.read() // consume \n
                return sb.toString()
            }
            if (c == '\n'.code) return sb.toString()
            sb.append(c.toChar())
        }
    }

    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            val fps = frameCount
            frameCount = 0
            lastFpsTime = now
            mainHandler.post { onFpsUpdate(fps) }
        }
    }

    fun disconnect() {
        isRunning = false
        try {
            connection?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }

    fun isConnected(): Boolean = isRunning
}