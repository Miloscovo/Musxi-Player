package tech.musxi.accounts

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import io.nayuki.qrcodegen.QrCode
import java.io.IOException

/** Only temporary login QR images are exported. No general media permission is needed. */
internal class TemporaryLoginImage(private val app: Context) {
    private val prefs = app.getSharedPreferences("temporary-netease-qr", Context.MODE_PRIVATE)
    fun save(payload: String, token: Long) {
        if (Build.VERSION.SDK_INT < 29) throw IOException("Gallery export requires Android 10")
        clear()
        val qr = QrCode.encodeText(payload, QrCode.Ecc.MEDIUM)
        val scale = 10; val border = 4; val size = (qr.size + border * 2) * scale
        val pixels = IntArray(size * size) { index ->
            if (qr.getModule((index % size) / scale - border, (index / size) / scale - border)) Color.BLACK else Color.WHITE
        }
        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        var image: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Musxi-login-${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MusxiLogin")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            image = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException()
            if (!prefs.edit().putString("uri", image.toString()).putLong("token", token).commit()) throw IOException()
            app.contentResolver.openOutputStream(image)?.use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException()
            } ?: throw IOException()
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (app.contentResolver.update(image, values, null, null) != 1) throw IOException()
        } catch (_: Exception) {
            image?.let { runCatching { app.contentResolver.delete(it, null, null) } }
            throw IOException("Temporary login image could not be saved")
        } finally { bitmap.recycle() }
    }

    fun clear(token: Long? = null) {
        if (token != null && prefs.getLong("token", -1) != token) return
        prefs.getString("uri", null)?.let {
            val uri = Uri.parse(it)
            if (uri.scheme == "content" && uri.authority == "media") app.contentResolver.delete(uri, null, null)
        }
        if (!prefs.edit().clear().commit()) throw IOException("Temporary image cleanup was not recorded")
    }
}
