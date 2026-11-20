package com.example.piphotos.net

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object QrCodeGenerator {

    fun generateBitMatrix(content: String, size: Int = 512): com.google.zxing.common.BitMatrix? {
        return try {
            val writer = QRCodeWriter()
            writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun generateQrBitmap(content: String, size: Int = 512): Bitmap? = withContext(Dispatchers.Default) {
        try {
            val bitMatrix = generateBitMatrix(content, size) ?: return@withContext null
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val offset = y * size
                for (x in 0 until size) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
        } catch (_: Exception) {
            null
        }
    }
}
