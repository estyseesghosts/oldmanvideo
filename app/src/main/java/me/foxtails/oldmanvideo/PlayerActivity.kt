package me.foxtails.oldmanvideo

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.Utils

class PlayerActivity : ComponentActivity() {
    private lateinit var surfaceView: SurfaceView
    private var isMpvInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val videoUri = intent.getStringExtra(VIDEO_URI_EXTRA) ?: run {
            finish()
            return
        }
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

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
        MPVLib.setOptionString("save-position-on-quit", "no")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.setOptionString("idle", "once")
        isMpvInitialized = true

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                MPVLib.attachSurface(holder.surface)
                MPVLib.setOptionString("force-window", "yes")
                MPVLib.command(arrayOf("loadfile", resolveVideoPath(videoUri)))
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                MPVLib.setPropertyString("vo", "null")
                MPVLib.setOptionString("force-window", "no")
                MPVLib.detachSurface()
            }
        })
    }

    override fun onBackPressed() {
        destroyMpv()
        finish()
    }

    override fun onDestroy() {
        destroyMpv()
        super.onDestroy()
    }

    private fun destroyMpv() {
        if (isMpvInitialized) {
            MPVLib.destroy()
            isMpvInitialized = false
        }
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