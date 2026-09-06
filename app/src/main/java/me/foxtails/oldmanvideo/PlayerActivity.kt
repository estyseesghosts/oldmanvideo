package me.foxtails.oldmanvideo

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.mutableFloatStateOf
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.Utils
import kotlin.math.roundToInt

class PlayerActivity : ComponentActivity() {
    private val playerViewModel: PlayerViewModel by viewModels()
    private lateinit var surfaceView: SurfaceView
    private var isMpvInitialized = false
    private var isMpvShuttingDown = false
    private var isSurfaceAttached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val videoUri = intent.getStringExtra(VIDEO_URI_EXTRA) ?: run {
            finish()
            return
        }
        surfaceView = SurfaceView(this)
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volumeFraction = mutableFloatStateOf(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat(),
        )
        val brightnessFraction = mutableFloatStateOf(window.attributes.screenBrightness)
        if (brightnessFraction.floatValue == -1f) {
            brightnessFraction.floatValue = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128,
            ) / 255f
        }

        val container = FrameLayout(this)
        container.addView(surfaceView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        container.addView(ComposeView(this).apply {
            setContent {
                PlayerControls(
                    vm = playerViewModel,
                    volumeFraction = volumeFraction.floatValue,
                    brightnessFraction = brightnessFraction.floatValue,
                    onVolumeChange = { fraction ->
                        volumeFraction.floatValue = fraction
                        audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            (fraction * maxVolume).roundToInt().coerceIn(0, maxVolume),
                            0,
                        )
                    },
                    onBrightnessChange = { fraction ->
                        brightnessFraction.floatValue = fraction
                        window.attributes = window.attributes.apply {
                            screenBrightness = fraction.coerceIn(0.01f, 1f)
                        }
                    },
                    onExit = { finish() },
                )
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(container)

        MPVLib.create(this)
        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", filesDir.resolve("mpv").path)
        MPVLib.setOptionString("gpu-shader-cache-dir", cacheDir.path)
        MPVLib.setOptionString("icc-cache-dir", cacheDir.path)
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("hwdec", "mediacodec,mediacodec-copy")
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("audio-set-media-role", "yes")
        MPVLib.setOptionString("tls-verify", "yes")
        MPVLib.setOptionString("tls-ca-file", filesDir.resolve("cacert.pem").path)
        MPVLib.setOptionString("input-default-bindings", "yes")
        MPVLib.setOptionString("demuxer-max-bytes", "33554432")
        MPVLib.setOptionString("demuxer-max-back-bytes", "33554432")
        MPVLib.init()
        MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        MPVLib.addObserver(playerViewModel)
        MPVLib.setOptionString("save-position-on-quit", "no")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.setOptionString("idle", "once")
        isMpvInitialized = true

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (!isMpvInitialized || isMpvShuttingDown) return
                MPVLib.attachSurface(holder.surface)
                isSurfaceAttached = true
                MPVLib.setOptionString("force-window", "yes")
                MPVLib.command(arrayOf("loadfile", resolveVideoPath(videoUri)))
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (!isSurfaceAttached || !isMpvInitialized || isMpvShuttingDown) return
                isSurfaceAttached = false
                MPVLib.setPropertyString("vo", "null")
                MPVLib.setOptionString("force-window", "no")
                MPVLib.detachSurface()
            }
        })
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        destroyMpv()
        super.onDestroy()
    }

    private fun destroyMpv() {
        if (!isMpvInitialized || isMpvShuttingDown) return
        isMpvShuttingDown = true
        isMpvInitialized = false

        MPVLib.removeObserver(playerViewModel)
        if (isSurfaceAttached) {
            isSurfaceAttached = false
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setOptionString("force-window", "no")
            MPVLib.detachSurface()
        }
        MPVLib.destroy()
    }

    private fun resolveVideoPath(uriString: String): String {
        val uri = android.net.Uri.parse(uriString)
        runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                Utils.findRealPath(descriptor.fd)
            }
        }.getOrNull()?.let { path -> return path }
        return uriString
    }

    companion object {
        const val VIDEO_URI_EXTRA = "video_uri"
    }
}