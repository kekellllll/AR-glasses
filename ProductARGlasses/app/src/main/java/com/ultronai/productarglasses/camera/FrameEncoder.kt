package com.ultronai.productarglasses.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

class FrameEncoder {

    fun encodeToJpeg(image: Image, quality: Int = 80): ByteArray? {
        return try {
            val nv21 = yuv420ToNv21(image)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val outputStream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, outputStream)
            outputStream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        val nv21 = ByteArray(width * height + width * (height / 2))

        // Copy Y plane (handle rowStride != width)
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, width * height)
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, row * width, width)
            }
        }

        // Copy VU interleaved for NV21
        val uvHeight = height / 2
        val uvWidth = width / 2
        var pos = width * height

        if (vPixelStride == 2 && uPixelStride == 2 && vRowStride == uRowStride) {
            // Already interleaved VUVU (common on most devices)
            if (vRowStride == width) {
                vBuffer.position(0)
                vBuffer.get(nv21, pos, width * uvHeight)
            } else {
                for (row in 0 until uvHeight) {
                    vBuffer.position(row * vRowStride)
                    vBuffer.get(nv21, pos + row * width, width)
                }
            }
        } else {
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val vIndex = row * vRowStride + col * vPixelStride
                    val uIndex = row * uRowStride + col * uPixelStride
                    nv21[pos++] = vBuffer.get(vIndex)
                    nv21[pos++] = uBuffer.get(uIndex)
                }
            }
        }

        return nv21
    }
}