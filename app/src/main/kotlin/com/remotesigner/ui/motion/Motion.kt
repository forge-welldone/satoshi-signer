package com.remotesigner.ui.motion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.unit.IntOffset

private const val SpinDurationMs = 800
private const val PulseDurationMs = 1400
const val SlideUpDurationMs = 280
const val FadeDurationMs = 220

@Composable
fun rememberSsSpin(): State<Float> {
    val transition = rememberInfiniteTransition(label = "ss-spin")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SpinDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ss-spin-deg",
    )
}

@Composable
fun rememberSsPulse(): State<Float> {
    val transition = rememberInfiniteTransition(label = "ss-pulse")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = PulseDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ss-pulse-progress",
    )
}

fun tweenSlideUp() = tween<IntOffset>(durationMillis = SlideUpDurationMs)

fun tweenFade() = tween<Float>(durationMillis = FadeDurationMs)
