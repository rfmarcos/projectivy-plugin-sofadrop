package com.rfmarcos.sofadrop

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object Qr {
    /** Black-on-white QR code of [text], [size] pixels wide (with a small quiet zone). */
    fun bitmap(text: String, size: Int, errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.M): Bitmap {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to errorCorrection),
        )
        val width = matrix.width
        val pixels = IntArray(width * width) { i -> if (matrix[i % width, i / width]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, width, width, Bitmap.Config.ARGB_8888)
    }

    /**
     * [text] with one pixel per module, scaled up without smoothing: sharp at any size, where a fixed-size
     * bitmap stretched by the view blurs the module edges a phone camera looks for.
     */
    fun drawable(resources: Resources, text: String, errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.M) =
        BitmapDrawable(resources, bitmap(text, 0, errorCorrection)).apply { isFilterBitmap = false }
}
