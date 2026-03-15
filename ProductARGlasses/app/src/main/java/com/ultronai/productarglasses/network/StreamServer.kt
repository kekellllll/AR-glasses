package com.ultronai.productarglasses.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.net.ServerSocket
import java.net.Socket

class StreamServer(
    private val port: Int = 8080,
    private val onClientConnected: (Boolean) -> Unit,
    private val onResultReceived: (String) -> Unit
) {
    companion object {
        private const val TAG = "StreamServer"
        // Magic bytes to mark frame start
        const val FRAME_MAGIC = 0x46524D45 // "FRME" in hex
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: BufferedReader? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var isRunning = false
    private val sendLock = kotlinx.coroutines.sync.Mutex()

    fun start() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Server started on port $port")

                while (isRunning) {
                    try {
                        Log.i(TAG, "Waiting for client...")
                        val socket = serverSocket?.accept() ?: break
                        handleClient(socket)
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting client", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        clientSocket?.close()
        clientSocket = socket

        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 512 * 1024

            outputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 256 * 1024))
            inputStream = BufferedReader(InputStreamReader(socket.getInputStream()))

            Log.i(TAG, "Client connected: ${socket.inetAddress}")
            withContext(Dispatchers.Main) {
                onClientConnected(true)
            }

            // Listen for results from mobile
            scope.launch {
                listenForResults()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
            withContext(Dispatchers.Main) {
                onClientConnected(false)
            }
        }
    }

    private suspend fun listenForResults() {
        try {
            while (isRunning && clientSocket?.isConnected == true) {
                val line = inputStream?.readLine() ?: break
                withContext(Dispatchers.Main) {
                    onResultReceived(line)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading results", e)
        } finally {
            withContext(Dispatchers.Main) {
                onClientConnected(false)
            }
        }
    }

    fun sendFrame(jpegData: ByteArray) {
        scope.launch {
            sendLock.withLock {
                try {
                    outputStream?.let { stream ->
                        stream.writeInt(FRAME_MAGIC)
                        stream.writeInt(jpegData.size)
                        stream.write(jpegData)
                        stream.flush()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending frame: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            inputStream?.close()
            outputStream?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
        scope.cancel()
    }

    fun isClientConnected(): Boolean {
        return clientSocket?.isConnected == true && !clientSocket!!.isClosed
    }
}