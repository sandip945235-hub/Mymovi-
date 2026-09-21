package com.mymovie.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@SuppressLint("UnsafeOptInUsageError")
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val RESULT_FAILED = 2
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var topBar: FrameLayout
    private lateinit var shield: FrameLayout
    private lateinit var unlockBtn: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var everPlayed = false
    private var retries = 0
    private var locked = false
    private var resumeOnReturn = false

    private val dimUnlock = Runnable { unlockBtn.animate().alpha(0.3f).setDuration(300).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val url = normalizeUrl(intent.getStringExtra("url").orEmpty())
        val title = intent.getStringExtra("title").orEmpty()
        val sub = intent.getStringExtra("sub").orEmpty()

        buildUi(title)
        hideSystemBars()
        startPlayer(url, sub)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (locked) {
                    revealUnlock()
                    Toast.makeText(
                        this@PlayerActivity,
                        "स्क्रीन लॉक है — 🔒 को दबाकर रखें",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    finish()
                }
            }
        })
    }

    // ---------- UI (सब कोड से बनता है, कोई layout फ़ाइल नहीं) ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int, g: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, g)

    private fun roundButton(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 20f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(150, 0, 0, 0))
        }
    }

    private fun buildUi(title: String) {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 3000
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        }
        root.addView(playerView, lp(MATCH, MATCH))

        // ऊपर की पट्टी: बंद करें, नाम, लॉक
        topBar = FrameLayout(this)
        topBar.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(200, 0, 0, 0), Color.TRANSPARENT)
        )
        val closeBtn = roundButton("✕").apply { setOnClickListener { finish() } }
        val lockBtn = roundButton("🔒").apply { setOnClickListener { setLocked(true) } }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        topBar.addView(closeBtn, lp(dp(46), dp(46), Gravity.START or Gravity.CENTER_VERTICAL))
        topBar.addView(lockBtn, lp(dp(46), dp(46), Gravity.END or Gravity.CENTER_VERTICAL))
        topBar.addView(
            titleView,
            lp(MATCH, WRAP, Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(56)
                rightMargin = dp(56)
            }
        )
        root.addView(topBar, lp(MATCH, WRAP, Gravity.TOP))

        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val i = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(i.left + dp(12), i.top + dp(10), i.right + dp(12), dp(16))
            insets
        }

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (!locked) topBar.visibility = visibility
            }
        )

        // स्क्रीन लॉक की परत: छूने पर कुछ नहीं होगा, अनलॉक के लिए बटन दबाकर रखना है
        shield = FrameLayout(this)
        shield.visibility = View.GONE
        shield.isClickable = true
        shield.setOnClickListener { revealUnlock() }
        unlockBtn = roundButton("🔒").apply {
            alpha = 0.3f
            setOnClickListener {
                revealUnlock()
                Toast.makeText(
                    this@PlayerActivity, "अनलॉक करने के लिए दबाकर रखें", Toast.LENGTH_SHORT
                ).show()
            }
            setOnLongClickListener {
                setLocked(false)
                true
            }
        }
        shield.addView(
            unlockBtn,
            lp(dp(64), dp(64), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(28) }
        )
        root.addView(shield, lp(MATCH, MATCH))

        setContentView(root)
    }

    private fun setLocked(on: Boolean) {
        locked = on
        if (on) {
            playerView.useController = false
            topBar.visibility = View.GONE
            shield.visibility = View.VISIBLE
            revealUnlock()
        } else {
            shield.visibility = View.GONE
            playerView.useController = true
            topBar.visibility = View.VISIBLE
            playerView.showController()
        }
    }

    private fun revealUnlock() {
        unlockBtn.animate().alpha(1f).setDuration(150).start()
        ui.removeCallbacks(dimUnlock)
        ui.postDelayed(dimUnlock, 2500)
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------- Media3 प्लेयर ----------

    private fun startPlayer(url: String, sub: String) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        // बड़ा बफ़र: शुरू जल्दी (1.5 सेकंड), और नेट धीमा हो तो भी न अटके
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 1_500, 4_000)
            .build()

        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.setAudioAttributes(AudioAttributes.DEFAULT, true)

        val item = MediaItem.Builder().setUri(url)
        mimeFor(url)?.let { item.setMimeType(it) }
        if (sub.startsWith("http", ignoreCase = true)) {
            val subMime =
                if (sub.contains(".vtt", ignoreCase = true)) MimeTypes.TEXT_VTT
                else MimeTypes.APPLICATION_SUBRIP
            item.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                        .setMimeType(subMime)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    everPlayed = true
                    retries = 0
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handleError(error)
            }
        })

        playerView.player = exo
        player = exo
        exo.setMediaItem(item.build())
        exo.playWhenReady = true
        exo.prepare()
    }

    private fun handleError(e: PlaybackException) {
        val p = player ?: return
        val netError = e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        val maxRetry = if (everPlayed) 6 else 1

        when {
            e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                p.seekToDefaultPosition()
                p.prepare()
            }
            netError && retries < maxRetry -> {
                retries++
                ui.postDelayed({ player?.prepare() }, 1500)
            }
            !everPlayed -> {
                // वीडियो कभी शुरू ही नहीं हुआ: वापस जाओ, पुराना प्लेयर आज़मा लेगा
                setResult(RESULT_FAILED)
                finish()
            }
            else -> Toast.makeText(
                this, "वीडियो चलने में दिक्कत आई। बंद करके फिर खोलें।", Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun mimeFor(u: String): String? {
        val l = u.lowercase()
        return when {
            l.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            l.contains(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    // Google Drive / Dropbox के शेयर लिंक को सीधे डाउनलोड लिंक में बदलता है
    private fun normalizeUrl(raw: String): String {
        var t = raw.trim().replace(" ", "%20")
        val drive = Regex("""drive\.google\.com/file/d/([^/?#]+)""").find(t)
        if (drive != null) {
            return "https://drive.google.com/uc?export=download&id=" + drive.groupValues[1]
        }
        if (t.contains("dropbox.com")) {
            t = t.replace("dl=0", "dl=1")
        }
        return t
    }

    override fun onPause() {
        super.onPause()
        resumeOnReturn = player?.playWhenReady == true
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (resumeOnReturn) player?.play()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
