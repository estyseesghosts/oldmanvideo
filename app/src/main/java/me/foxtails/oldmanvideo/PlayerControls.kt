package me.foxtails.oldmanvideo

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun PlayerControls(
    vm: PlayerViewModel,
    volumeFraction: Float,
    brightnessFraction: Float,
    subtitleText: String,
    subtitleAvailable: Boolean,
    onVolumeChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onToggleSubtitles: () -> Unit,
    onExit: () -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(false) }
    var interacting by remember { mutableStateOf(false) }
    var seekPreview by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(controlsVisible, interacting) {
        if (controlsVisible && !interacting) {
            delay(2000)
            controlsVisible = false
        }
    }

    fun show() {
        controlsVisible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { show() })
            },
    ) {
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.10f)),
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                IconButton(
                    onClick = onExit,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(24.dp)
                        .size(64.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Exit",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }

                if (subtitleAvailable) {
                    Button(
                        onClick = {
                            onToggleSubtitles()
                            show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (vm.subtitlesEnabled) {
                                androidx.compose.material3.MaterialTheme.colorScheme.primary
                            } else {
                                Color.Gray
                            },
                        ),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(24.dp)
                            .height(64.dp),
                    ) {
                        Text(
                            text = if (vm.subtitlesEnabled) "CC ON" else "CC OFF",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        )
                    }
                }

                IconButton(
                    onClick = { vm.togglePause(); show() },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(96.dp),
                ) {
                    Icon(
                        imageVector = if (vm.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (vm.isPaused) "Play" else "Pause",
                        tint = Color.White,
                        modifier = Modifier.size(64.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 24.dp)
                        .height(64.dp),
                ) {
                    Slider(
                        value = seekPreview ?: vm.positionSeconds.toFloat(),
                        valueRange = 0f..maxOf(vm.durationSeconds.toFloat(), 0.01f),
                        onValueChange = {
                            interacting = true
                            seekPreview = it
                            show()
                        },
                        onValueChangeFinished = {
                            seekPreview?.let { vm.seekTo(it.toDouble()) }
                            seekPreview = null
                            interacting = false
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    )
                    Text(
                        text = formatTimeRemaining(
                            vm.durationSeconds - (seekPreview ?: vm.positionSeconds.toFloat()).toDouble(),
                        ),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }

                VerticalRail(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp)
                        .width(72.dp)
                        .fillMaxHeight(0.6f),
                    fraction = volumeFraction,
                    label = "V",
                    onDrag = { onVolumeChange(it); show() },
                    onStart = { interacting = true; show() },
                    onEnd = { interacting = false },
                )

                VerticalRail(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 24.dp)
                        .width(72.dp)
                        .fillMaxHeight(0.6f),
                    fraction = brightnessFraction,
                    label = "B",
                    onDrag = { onBrightnessChange(it); show() },
                    onStart = { interacting = true; show() },
                    onEnd = { interacting = false },
                )
            }
        }
    }
}

@Composable
fun VerticalRail(
    modifier: Modifier,
    fraction: Float,
    label: String,
    onDrag: (Float) -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
) {
    Box(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(32.dp))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { onStart() },
                    onDragEnd = { onEnd() },
                    onVerticalDrag = { change, _ ->
                        onDrag((1f - change.position.y / size.height).coerceIn(0f, 1f))
                    },
                )
            },
    ) {
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(fraction.coerceIn(0f, 1f))
                .background(Color.White, RoundedCornerShape(32.dp)),
        )
    }
}

private fun formatTimeRemaining(seconds: Double): String {
    val totalSeconds = seconds.coerceAtLeast(0.0).toLong()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val remainingSeconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    } else {
        "%02d:%02d".format(minutes, remainingSeconds)
    }
}
