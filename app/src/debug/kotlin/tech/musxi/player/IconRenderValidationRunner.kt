package tech.musxi.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import java.io.File
import java.io.FileOutputStream

/** Renders the installed launcher icon to PNG files for edge inspection. Debug variant only. */
internal object IconRenderValidationRunner {
    private const val SIZE = 432

    fun run(app: MusxiApplication, step: String): Boolean {
        if (step != "icon_render") return false
        val diag = app.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        val result = try {
            val dir = requireNotNull(app.getExternalFilesDir("icon_render")) { "external files dir unavailable" }
            dir.mkdirs()
            val d = app.packageManager.getApplicationIcon(app.packageName)
            if (d is AdaptiveIconDrawable) renderAdaptive(d, dir) else renderPlain(d, dir)
            "OK:${dir.absolutePath}"
        } catch (e: Exception) {
            "FAILED:${e.javaClass.simpleName}"
        }
        diag.edit().putString("iconRender", result).apply()
        return true
    }

    private fun renderPlain(d: Drawable, dir: File) {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, SIZE, SIZE)
        d.draw(c)
        save(bmp, File(dir, "full.png"))
    }

    private fun renderAdaptive(d: AdaptiveIconDrawable, dir: File) {
        val bg = d.background
        val fg = d.foreground
        fun drawContent(c: Canvas) {
            bg?.setBounds(0, 0, SIZE, SIZE)
            bg?.draw(c)
            fg?.setBounds(0, 0, SIZE, SIZE)
            fg?.draw(c)
        }

        // a) full: no mask, background then foreground.
        val full = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        drawContent(Canvas(full))
        save(full, File(dir, "full.png"))

        // b) circle: 72dp/108dp visible area, circle radius size/3.
        val circle = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val cc = Canvas(circle)
        cc.clipPath(Path().apply { addCircle(SIZE / 2f, SIZE / 2f, SIZE / 3f, Path.Direction.CW) })
        drawContent(cc)
        save(circle, File(dir, "circle.png"))

        // c) system: iconMask (100x100 units) scaled to size, then whole canvas scaled to 72/108 about center.
        val system = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val sc = Canvas(system)
        val mask = Path(d.iconMask).apply {
            transform(Matrix().apply { setScale(SIZE / 100f, SIZE / 100f) })
        }
        sc.save()
        sc.scale(2f / 3f, 2f / 3f, SIZE / 2f, SIZE / 2f)
        sc.clipPath(mask)
        drawContent(sc)
        sc.restore()
        save(system, File(dir, "system.png"))
    }

    private fun save(bmp: Bitmap, file: File) {
        try {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bmp.recycle()
        }
    }
}
