package tech.musxi.player

import android.os.Bundle
import android.view.MotionEvent
import android.widget.TextView
import tech.musxi.player.lockscreen.LockScreenLyricsHostActivity

/** Mechanism probe only, not a product page. No lyrics, wallpaper, transport or account controls. */
class LockScreenPageProbeActivity : LockScreenLyricsHostActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val threshold = 80 * resources.displayMetrics.density
        var downX = 0f
        var downY = 0f
        setContentView(TextView(this).apply {
            text = "Musxi 锁屏宿主探针\n向任意方向滑动或按返回，回到系统锁屏"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.BLACK)
            gravity = android.view.Gravity.CENTER
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
                    MotionEvent.ACTION_UP -> if (maxOf(kotlin.math.abs(event.x - downX),
                            kotlin.math.abs(event.y - downY)) >= threshold) dismissToSystemLockScreen()
                }
                true
            }
        })
    }
}
