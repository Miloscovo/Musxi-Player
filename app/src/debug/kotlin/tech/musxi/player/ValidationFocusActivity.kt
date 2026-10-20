package tech.musxi.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Real Android focus requests, debug only. A foreground Activity meets API 35+ policy.
 * Finite timeout releases focus even when an adb acceptance script is interrupted.
 */
class ValidationFocusActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private var request: AudioFocusRequest? = null
    private val expiry = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getLongExtra("revision", -1) != revision) { finish(); return }
        current?.finish()
        current = this
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val permanent = intent.getBooleanExtra("permanent", false)
        val gain = if (permanent) AudioManager.AUDIOFOCUS_GAIN else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        val focus = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ change ->
                getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putInt("focusProbeLastChange", change).apply()
            }, handler).build()
        request = focus
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
            .putBoolean("focusProbeActive", true).putBoolean("focusProbePermanent", permanent)
            .putInt("focusProbeResult", audio.requestAudioFocus(focus)).apply()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
            addView(TextView(this@ValidationFocusActivity).apply {
                text = "音频焦点验收正在进行，30 秒后自动归还。"; textSize = 20f
            })
            addView(Button(this@ValidationFocusActivity).apply {
                text = "归还焦点并返回"; setOnClickListener { finish() }
            })
        })
        handler.postDelayed(expiry, 30_000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(expiry)
        request?.let { audio.abandonAudioFocusRequest(it) }; request = null
        if (current === this) {
            current = null
            getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putBoolean("focusProbeActive", false).apply()
        }
        super.onDestroy()
    }

    companion object {
        private var current: ValidationFocusActivity? = null
        private var revision = 0L
        fun launch(context: Context, permanent: Boolean) {
            val token = ++revision
            context.startActivity(Intent(context, ValidationFocusActivity::class.java)
                .putExtra("permanent", permanent).putExtra("revision", token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun release() { ++revision; current?.finish() }
    }
}
