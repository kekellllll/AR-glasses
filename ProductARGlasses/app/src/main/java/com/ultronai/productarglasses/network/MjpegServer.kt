package com.ultronai.productarglasses.network

import android.util.Log
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class MjpegServer(
    private val port: Int = 8080,
    private val onClientConnected: (Int) -> Unit
) {
    companion object {
        private const val TAG = "MjpegServer"
        private const val BOUNDARY = "frame"
    }

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<ClientHandler>()

    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true

        thread {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "MJPEG Server started on port $port")

                while (isRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        handleNewClient(socket)
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

    private fun handleNewClient(socket: Socket) {
        thread {
            try {
                val input = socket.getInputStream().bufferedReader()
                val output = BufferedOutputStream(socket.getOutputStream())

                // Read HTTP request (we don't really care about it)
                var line = input.readLine()
                while (line != null && line.isNotEmpty()) {
                    line = input.readLine()
                }

                // Send HTTP response header
                val header = """
                    HTTP/1.1 200 OK
                    Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY
                    Cache-Control: no-cache
                    Connection: keep-alive
                    
                """.trimIndent().replace("\n", "\r\n") + "\r\n"

                output.write(header.toByteArray())
                output.flush()

                val client = ClientHandler(socket, output)
                clients.add(client)

                Log.i(TAG, "Client connected. Total: ${clients.size}")
                onClientConnected(clients.size)

                socket.soTimeout = 5000
                val detector = socket.getInputStream()
                while (isRunning && !socket.isClosed) {
                    try {
                        if (detector.read() == -1) break
                    } catch (_: java.net.SocketTimeoutException) {
                        // no data is fine, just keep checking
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Client error", e)
            } finally {
                removeClient(socket)
            }
        }
    }

    fun sendFrame(jpegData: ByteArray) {
        val header = """
            --$BOUNDARY
            Content-Type: image/jpeg
            Content-Length: ${jpegData.size}
            
        """.trimIndent().replace("\n", "\r\n") + "\r\n"

        val headerBytes = header.toByteArray()

        clients.forEach { client ->
            try {
                client.output.write(headerBytes)
                client.output.write(jpegData)
                client.output.write("\r\n".toByteArray())
                client.output.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send to client, removing")
                removeClient(client.socket)
            }
        }
    }

    private fun removeClient(socket: Socket) {
        clients.removeAll { it.socket == socket }
        try {
            socket.close()
        } catch (e: Exception) {}
        onClientConnected(clients.size)
        Log.i(TAG, "Client disconnected. Total: ${clients.size}")
    }

    fun stop() {
        isRunning = false
        clients.forEach {
            try { it.socket.close() } catch (e: Exception) {}
        }
        clients.clear()
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
    }

    fun getClientCount() = clients.size

    private data class ClientHandler(
        val socket: Socket,
        val output: OutputStream
    )
}