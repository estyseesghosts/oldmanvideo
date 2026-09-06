package me.foxtails.oldmanvideo

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import `is`.xyz.mpv.MPVLib

class PlayerViewModel : ViewModel(), MPVLib.EventObserver {
    var isPaused by mutableStateOf(false)
        private set
    var positionSeconds by mutableStateOf(0.0)
        private set
    var durationSeconds by mutableStateOf(0.0)
        private set
    var subtitleText by mutableStateOf("")
        private set
    var subtitleAvailable by mutableStateOf(false)
        private set
    var subtitlesEnabled by mutableStateOf(false)
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var externalSubtitleTrackId: String? = null

    fun togglePause() {
        isPaused = !isPaused
        MPVLib.setPropertyBoolean("pause", isPaused)
    }

    fun toggleSubtitles() {
        if (subtitlesEnabled) {
            MPVLib.setPropertyString("sid", "no")
            subtitleText = ""
        } else {
            externalSubtitleTrackId?.let { trackId ->
                MPVLib.setPropertyString("sid", trackId)
            }
        }
    }

    fun setExternalSubtitleAvailable(available: Boolean) {
        subtitleAvailable = available
    }

    fun seekTo(seconds: Double) {
        MPVLib.setPropertyDouble("time-pos", seconds)
    }

    override fun eventProperty(property: String, value: Double) {
        mainHandler.post {
            when (property) {
                "time-pos" -> positionSeconds = value
                "duration" -> durationSeconds = value
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        if (property == "pause") {
            mainHandler.post { isPaused = value }
        }
    }

    override fun eventProperty(property: String) = Unit

    override fun eventProperty(property: String, value: Long) = Unit

    override fun eventProperty(property: String, value: String) {
        mainHandler.post {
            when (property) {
                "sub-text" -> subtitleText = value
                "sid" -> {
                    subtitlesEnabled = value.isNotBlank() && value != "no" && value != "0"
                    if (subtitlesEnabled) {
                        externalSubtitleTrackId = value
                        subtitleAvailable = true
                    } else {
                        subtitleText = ""
                    }
                }
            }
        }
    }

    override fun event(eventId: Int) {
        if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
            mainHandler.post { onFileLoaded?.invoke() }
        }
    }

    var onFileLoaded: (() -> Unit)? = null
        set(value) {
            field = value
        }

    override fun onCleared() {
        mainHandler.removeCallbacksAndMessages(null)
        onFileLoaded = null
        MPVLib.removeObserver(this)
        super.onCleared()
    }
}