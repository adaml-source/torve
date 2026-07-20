package com.torve.android.ui.splash

import android.media.MediaPlayer
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.android.R
import com.torve.android.ui.theme.JakartaSans
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ── Phase state machine ──
private enum class SplashPhase {
    DARK,
    EYE_OPENING,
    LETTERS_EMERGING,
    HOLD,
    CONSUMPTION,
    PORTAL,
}

// ── Colors ──
private val AmberMain = Color(0xFFD4A03C)
private val AmberHot = Color(0xFFECC44E)
private val AmberDeep = Color(0xFFB87A22)
private val Void = Color(0xFF050507)
private val Snow = Color(0xFFF5F5F7)
private val Silver = Color(0xFFB8B8C7)

// ── Letter metadata ──
private data class LetterDef(
    val char: Char,
    val emergeDelay: Long,
    val spiralStartAngle: Float,
    val spiralRotations: Float,
)

private val LETTERS = listOf(
    LetterDef('T', 0L, PI.toFloat(), 1.2f),
    // O is the eye — not in this list
    LetterDef('R', 120L, 0.3f, 1.0f),
    LetterDef('V', 240L, -PI.toFloat() / 3f, 1.5f),
    LetterDef('E', 360L, PI.toFloat() / 4f, 2.0f),
)

@Composable
fun TorveEyeSplashScreen(
    onSplashComplete: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    // ── State ──
    var phase by remember { mutableStateOf(SplashPhase.DARK) }

    // Animated progress values for each phase (0→1)
    var eyeOpenProgress by remember { mutableFloatStateOf(0f) }
    var letterEmergeProgress by remember { mutableFloatStateOf(0f) }
    var consumptionProgress by remember { mutableFloatStateOf(0f) }
    var portalProgress by remember { mutableFloatStateOf(0f) }
    var taglineAlpha by remember { mutableFloatStateOf(0f) }
    // Cumulative rotation angle for the eye
    var eyeRotation by remember { mutableFloatStateOf(0f) }

    // ── Sound ──
    // Start silent — fade-in prevents the transient pop from MP3 decoder priming
    val targetVolume = 0.65f // moderate loudness, avoids speaker distortion
    val mediaPlayer = remember {
        try {
            MediaPlayer.create(context, R.raw.torve_sting_dark)?.apply {
                setVolume(0f, 0f)
            }
        } catch (_: Exception) { null }
    }
    val soundReleased = remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            if (!soundReleased.value) {
                soundReleased.value = true
                try {
                    if (mediaPlayer?.isPlaying == true) mediaPlayer.stop()
                    mediaPlayer?.release()
                } catch (_: Exception) {}
            }
        }
    }

    // ── Phase state machine ──
    LaunchedEffect(Unit) {
        // Start playback then fade in over ~80ms to mask MP3 decoder priming pop
        mediaPlayer?.start()
        for (step in 1..8) {
            val vol = targetVolume * step / 8f
            try { mediaPlayer?.setVolume(vol, vol) } catch (_: Exception) {}
            delay(10)
        }
        delay(20) // remaining time to align with original 100ms delay

        // Act 1: Eye opening
        phase = SplashPhase.EYE_OPENING
        val eyeOpenStart = System.currentTimeMillis()
        // Animate eye opening over 400ms with overshoot
        while (eyeOpenProgress < 1f) {
            val elapsed = (System.currentTimeMillis() - eyeOpenStart).toFloat()
            eyeOpenProgress = (elapsed / 400f).coerceIn(0f, 1f)
            delay(16)
        }

        // Letters emerge from eye center
        phase = SplashPhase.LETTERS_EMERGING
        val emergeStart = System.currentTimeMillis()
        while (letterEmergeProgress < 1f) {
            val elapsed = (System.currentTimeMillis() - emergeStart).toFloat()
            letterEmergeProgress = (elapsed / 800f).coerceIn(0f, 1f)
            delay(16)
        }

        // Act 2: Hold — brand moment
        phase = SplashPhase.HOLD
        // Fade in tagline at ~400ms into hold
        val holdStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - holdStart < 1600L) {
            val elapsed = (System.currentTimeMillis() - holdStart).toFloat()
            taglineAlpha = ((elapsed - 400f) / 400f).coerceIn(0f, 1f)
            delay(16)
        }

        // Act 3: Consumption — black hole pulls letters in
        phase = SplashPhase.CONSUMPTION
        val consumeStart = System.currentTimeMillis()
        while (consumptionProgress < 1f) {
            val elapsed = (System.currentTimeMillis() - consumeStart).toFloat()
            consumptionProgress = (elapsed / 1000f).coerceIn(0f, 1f)
            delay(16)
        }

        // Act 4: Portal — zoom into eye
        phase = SplashPhase.PORTAL
    }

    // ── Sound fade-out during portal phase ──
    LaunchedEffect(phase) {
        if (phase == SplashPhase.PORTAL) {
            val portalStart = System.currentTimeMillis()
            val steps = 20
            val stepDelay = 25L // 20 × 25ms = 500ms
            for (i in steps downTo 0) {
                val vol = targetVolume * i.toFloat() / steps
                try { mediaPlayer?.setVolume(vol, vol) } catch (_: Exception) {}
                // Also advance portal progress
                portalProgress = ((System.currentTimeMillis() - portalStart).toFloat() / 500f)
                    .coerceIn(0f, 1f)
                delay(stepDelay)
            }
            if (!soundReleased.value) {
                soundReleased.value = true
                try {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                } catch (_: Exception) {}
            }
            onSplashComplete()
        }
    }

    // ── Continuous eye rotation (accelerates in consumption/portal) ──
    LaunchedEffect(phase) {
        var lastFrame = System.currentTimeMillis()
        while (phase != SplashPhase.DARK) {
            val now = System.currentTimeMillis()
            val dt = (now - lastFrame).toFloat() / 1000f
            lastFrame = now

            val degreesPerSecond = when (phase) {
                SplashPhase.DARK -> 0f
                SplashPhase.EYE_OPENING -> 30f
                SplashPhase.LETTERS_EMERGING -> 40f
                SplashPhase.HOLD -> 60f // slow, elegant rotation
                SplashPhase.CONSUMPTION -> {
                    // Accelerate from 60 to 720 deg/sec
                    60f + consumptionProgress * 660f
                }
                SplashPhase.PORTAL -> 900f
            }
            eyeRotation += degreesPerSecond * dt
            delay(16)
        }
    }

    // ── Portal zoom animation ──
    val portalZoom by animateFloatAsState(
        targetValue = if (phase == SplashPhase.PORTAL) 10f else 1f,
        animationSpec = tween(500, easing = FastOutLinearInEasing),
        label = "portalZoom",
    )
    val portalFlash by animateFloatAsState(
        targetValue = if (phase == SplashPhase.PORTAL) 1f else 0f,
        animationSpec = tween(400, delayMillis = 100),
        label = "portalFlash",
    )

    // ── Idle floating offsets for letters (Act 2) ──
    val infiniteTransition = rememberInfiniteTransition(label = "idle")
    val float0 by infiniteTransition.animateFloat(
        initialValue = -1.5f, targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(2200, easing = EaseInOut)),
        label = "f0",
    )
    val float1 by infiniteTransition.animateFloat(
        initialValue = 1.0f, targetValue = -1.0f,
        animationSpec = infiniteRepeatable(tween(2600, easing = EaseInOut)),
        label = "f1",
    )
    val float2 by infiniteTransition.animateFloat(
        initialValue = -0.8f, targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(3000, easing = EaseInOut)),
        label = "f2",
    )
    val float3 by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = -1.5f,
        animationSpec = infiniteRepeatable(tween(2400, easing = EaseInOut)),
        label = "f3",
    )
    val idleFloats = listOf(float0, float1, float2, float3)

    // ── Core glow pulse during hold ──
    val corePulse by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(1500, easing = EaseInOut)),
        label = "corePulse",
    )

    // ── Rendering ──
    Box(
        modifier = Modifier.fillMaxSize().background(Void),
        contentAlignment = Alignment.Center,
    ) {
        // Scaled content for portal zoom
        Canvas(
            modifier = Modifier.fillMaxSize(),
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val dp1 = 1.dp.toPx()

            // ── Letter layout calculations (before scale so we can use oSlotCenterX as pivot) ──
            val letterSpacing = 12.sp.toPx()
            val letterStyle = TextStyle(
                fontFamily = JakartaSans,
                fontWeight = FontWeight.Bold,
                fontSize = 42.sp,
                letterSpacing = 0.sp,
            )

            val tLayout = textMeasurer.measure("T", letterStyle)
            val rLayout = textMeasurer.measure("R", letterStyle)
            val vLayout = textMeasurer.measure("V", letterStyle)
            val eLayout = textMeasurer.measure("E", letterStyle)

            val letterWidths = listOf(
                tLayout.size.width.toFloat(),
                rLayout.size.width.toFloat(),
                vLayout.size.width.toFloat(),
                eLayout.size.width.toFloat(),
            )
            val letterHeight = tLayout.size.height.toFloat()

            val eyeSlotWidth = letterHeight * 0.85f

            val totalWidth = letterWidths[0] + letterSpacing +
                eyeSlotWidth + letterSpacing +
                letterWidths[1] + letterSpacing +
                letterWidths[2] + letterSpacing +
                letterWidths[3]

            val wordStartX = cx - totalWidth / 2f
            val baseY = cy - letterHeight / 2f

            val tFinalX = wordStartX
            val oSlotCenterX = wordStartX + letterWidths[0] + letterSpacing + eyeSlotWidth / 2f

            // Portal zoom: scale everything from the eye center
            scale(portalZoom, portalZoom, pivot = Offset(oSlotCenterX, cy)) {
                val rFinalX = wordStartX + letterWidths[0] + letterSpacing + eyeSlotWidth + letterSpacing
                val vFinalX = rFinalX + letterWidths[1] + letterSpacing
                val eFinalX = vFinalX + letterWidths[2] + letterSpacing

                // Letter center X positions (for spiral origin calculations)
                val letterFinalCenterXs = listOf(
                    tFinalX + letterWidths[0] / 2f,
                    rFinalX + letterWidths[1] / 2f,
                    vFinalX + letterWidths[2] / 2f,
                    eFinalX + letterWidths[3] / 2f,
                )
                val letterFinalXs = listOf(tFinalX, rFinalX, vFinalX, eFinalX)

                // Eye dimensions — match letter height
                val eyeRingRadiusX = eyeSlotWidth / 2f * 1.1f
                val eyeRingRadiusY = eyeRingRadiusX * 0.45f // elliptical flattening

                // ── Eye scale (Act 1: overshoot from 0 to 1) ──
                val eyeScale = if (phase == SplashPhase.DARK) 0f
                else {
                    val raw = eyeOpenProgress
                    // Overshoot ease-out: goes to ~1.15 then settles to 1.0
                    if (raw < 0.7f) {
                        val t = raw / 0.7f
                        t * 1.18f
                    } else {
                        val t = (raw - 0.7f) / 0.3f
                        1.18f - 0.18f * t
                    }
                }

                // Eye size adjustments per phase
                val eyeSizeMultiplier = when (phase) {
                    SplashPhase.DARK -> 0f
                    SplashPhase.EYE_OPENING -> 1.8f - 0.8f * eyeOpenProgress // starts big, shrinks to letter size
                    SplashPhase.LETTERS_EMERGING -> 1f
                    SplashPhase.HOLD -> 1f
                    SplashPhase.CONSUMPTION -> 1f + consumptionProgress * 0.5f // grows slightly
                    SplashPhase.PORTAL -> 1.5f
                }

                // ── Core glow radius ──
                val coreRadius = when (phase) {
                    SplashPhase.DARK -> 0f
                    SplashPhase.EYE_OPENING -> 20f * dp1 * eyeOpenProgress
                    SplashPhase.LETTERS_EMERGING -> 20f * dp1
                    SplashPhase.HOLD -> (15f + 10f * corePulse) * dp1
                    SplashPhase.CONSUMPTION -> (20f + 40f * consumptionProgress) * dp1
                    SplashPhase.PORTAL -> (60f + 200f * portalProgress) * dp1
                }

                val coreAlpha = when (phase) {
                    SplashPhase.DARK -> 0f
                    SplashPhase.EYE_OPENING -> 0.3f * eyeOpenProgress
                    SplashPhase.LETTERS_EMERGING -> 0.35f
                    SplashPhase.HOLD -> corePulse
                    SplashPhase.CONSUMPTION -> 0.5f + 0.4f * consumptionProgress
                    SplashPhase.PORTAL -> 0.9f + 0.1f * portalProgress
                }

                // ── Shake during consumption ──
                val shakeX = if (phase == SplashPhase.CONSUMPTION) {
                    (kotlin.random.Random.nextFloat() - 0.5f) * 4f * consumptionProgress
                } else 0f
                val shakeY = if (phase == SplashPhase.CONSUMPTION) {
                    (kotlin.random.Random.nextFloat() - 0.5f) * 4f * consumptionProgress
                } else 0f

                // ── Draw ambient core glow ──
                if (coreAlpha > 0f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                AmberHot.copy(alpha = coreAlpha),
                                AmberMain.copy(alpha = coreAlpha * 0.5f),
                                Color.Transparent,
                            ),
                            center = Offset(oSlotCenterX + shakeX, cy + shakeY),
                            radius = coreRadius,
                        ),
                        radius = coreRadius,
                        center = Offset(oSlotCenterX + shakeX, cy + shakeY),
                    )
                }

                // ── Draw the eye orbital ring ──
                if (eyeScale > 0f) {
                    val erx = eyeRingRadiusX * eyeScale * eyeSizeMultiplier
                    val ery = eyeRingRadiusY * eyeScale * eyeSizeMultiplier

                    // Orbital ring with 3D tilt
                    rotate(
                        degrees = eyeRotation,
                        pivot = Offset(oSlotCenterX + shakeX, cy + shakeY),
                    ) {
                        // Main ring
                        drawOval(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    AmberHot.copy(alpha = 0.85f),
                                    AmberDeep.copy(alpha = 0.5f),
                                ),
                            ),
                            topLeft = Offset(
                                oSlotCenterX - erx + shakeX,
                                cy - ery + shakeY,
                            ),
                            size = Size(erx * 2f, ery * 2f),
                            style = Stroke(
                                width = 2.5f * dp1,
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(12f * dp1, 4f * dp1),
                                ),
                            ),
                        )
                    }

                    // Second ring — offset rotation for depth
                    rotate(
                        degrees = -eyeRotation * 0.7f + 60f,
                        pivot = Offset(oSlotCenterX + shakeX, cy + shakeY),
                    ) {
                        drawOval(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    AmberMain.copy(alpha = 0.4f),
                                    AmberDeep.copy(alpha = 0.2f),
                                ),
                            ),
                            topLeft = Offset(
                                oSlotCenterX - erx * 0.85f + shakeX,
                                cy - ery * 0.85f + shakeY,
                            ),
                            size = Size(erx * 1.7f, ery * 1.7f),
                            style = Stroke(
                                width = 1.5f * dp1,
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(6f * dp1, 8f * dp1),
                                ),
                            ),
                        )
                    }
                }

                // ── Draw letters ──
                for (i in LETTERS.indices) {
                    val letter = LETTERS[i]
                    val finalX = letterFinalXs[i]
                    val finalCenterX = letterFinalCenterXs[i]
                    val width = letterWidths[i]

                    // Calculate letter position based on phase
                    var letterX: Float
                    var letterY: Float
                    var letterAlpha: Float
                    var letterScale = 1f
                    var glowAlpha = 0f

                    when (phase) {
                        SplashPhase.DARK -> {
                            letterX = oSlotCenterX - width / 2f
                            letterY = baseY
                            letterAlpha = 0f
                        }
                        SplashPhase.EYE_OPENING -> {
                            letterX = oSlotCenterX - width / 2f
                            letterY = baseY
                            letterAlpha = 0f
                        }
                        SplashPhase.LETTERS_EMERGING -> {
                            // Staggered emergence from eye center
                            val staggeredProgress = ((letterEmergeProgress * 800f - letter.emergeDelay) / (800f - letter.emergeDelay))
                                .coerceIn(0f, 1f)
                            // Ease-out for smooth deceleration
                            val easedProgress = 1f - (1f - staggeredProgress) * (1f - staggeredProgress)

                            val startX = oSlotCenterX - width / 2f
                            letterX = startX + (finalX - startX) * easedProgress
                            letterY = baseY
                            letterAlpha = staggeredProgress
                            letterScale = 0.3f + 0.7f * easedProgress
                            // Glow trail during emergence
                            glowAlpha = if (staggeredProgress in 0.05f..0.8f) {
                                (1f - staggeredProgress) * 0.6f
                            } else 0f
                        }
                        SplashPhase.HOLD -> {
                            letterX = finalX
                            letterY = baseY + idleFloats[i] * dp1
                            letterAlpha = 1f
                        }
                        SplashPhase.CONSUMPTION -> {
                            // Spiral path back to eye center
                            // Stagger: E first (index 3), then V, R, T
                            val consumeOrder = listOf(0.15f, 0.05f, 0.10f, 0.0f) // delay fraction
                            val staggeredT = ((consumptionProgress - consumeOrder[i]) / (1f - consumeOrder[i]))
                                .coerceIn(0f, 1f)
                            // EaseIn — slow start, accelerating (gravitational pull)
                            val easedT = staggeredT * staggeredT

                            val distX = finalCenterX - oSlotCenterX
                            val distY = 0f // letters are at same Y as eye center
                            val startRadius = kotlin.math.sqrt(distX * distX + distY * distY)

                            val spiralPos = spiralPosition(
                                t = easedT,
                                startAngle = letter.spiralStartAngle,
                                startRadius = startRadius,
                                rotations = letter.spiralRotations,
                            )

                            letterX = oSlotCenterX + spiralPos.x - width / 2f
                            letterY = cy + spiralPos.y - letterHeight / 2f
                            letterAlpha = 1f
                            letterScale = 1f - easedT * 0.85f // shrink as approaching center
                            // Glow intensifies near center (event horizon heating)
                            glowAlpha = easedT * 0.8f
                        }
                        SplashPhase.PORTAL -> {
                            // Letters are consumed — invisible
                            letterX = oSlotCenterX - width / 2f
                            letterY = baseY
                            letterAlpha = 0f
                        }
                    }

                    letterX += shakeX
                    letterY += shakeY

                    if (letterAlpha > 0f) {
                        // Amber glow behind letter
                        if (glowAlpha > 0f) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        AmberHot.copy(alpha = glowAlpha),
                                        Color.Transparent,
                                    ),
                                    center = Offset(
                                        letterX + width / 2f,
                                        letterY + letterHeight / 2f,
                                    ),
                                    radius = letterHeight * 0.8f,
                                ),
                                radius = letterHeight * 0.8f,
                                center = Offset(
                                    letterX + width / 2f,
                                    letterY + letterHeight / 2f,
                                ),
                            )
                        }

                        // Draw the letter
                        val layout = textMeasurer.measure(
                            text = letter.char.toString(),
                            style = letterStyle.copy(
                                color = Snow.copy(alpha = letterAlpha),
                            ),
                        )

                        // Apply scale around letter center
                        if (letterScale != 1f) {
                            scale(
                                letterScale,
                                letterScale,
                                pivot = Offset(
                                    letterX + width / 2f,
                                    letterY + letterHeight / 2f,
                                ),
                            ) {
                                drawText(
                                    textLayoutResult = layout,
                                    topLeft = Offset(letterX, letterY),
                                )
                            }
                        } else {
                            drawText(
                                textLayoutResult = layout,
                                topLeft = Offset(letterX, letterY),
                            )
                        }
                    }
                }

                // ── Tagline: "See Everything" ──
                if (phase == SplashPhase.HOLD || (phase == SplashPhase.CONSUMPTION && consumptionProgress < 0.3f)) {
                    val tagAlpha = if (phase == SplashPhase.CONSUMPTION) {
                        taglineAlpha * (1f - consumptionProgress / 0.3f)
                    } else {
                        taglineAlpha
                    }
                    if (tagAlpha > 0f) {
                        val tagStyle = TextStyle(
                            fontFamily = JakartaSans,
                            fontWeight = FontWeight.Normal,
                            fontSize = 14.sp,
                            letterSpacing = 4.sp,
                            color = Silver.copy(alpha = tagAlpha),
                        )
                        val tagLayout = textMeasurer.measure("See Everything", tagStyle)
                        drawText(
                            textLayoutResult = tagLayout,
                            topLeft = Offset(
                                cx - tagLayout.size.width / 2f + shakeX,
                                baseY + letterHeight + 24f * dp1 + shakeY,
                            ),
                        )
                    }
                }
            }
        }

        // ── Portal flash overlay ──
        if (portalFlash > 0f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            AmberHot.copy(alpha = portalFlash * 0.9f),
                            AmberMain.copy(alpha = portalFlash * 0.6f),
                            Void.copy(alpha = portalFlash * 0.3f),
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.width,
                    ),
                    size = size,
                )
            }
        }
    }
}

/**
 * Compute a spiral-in position for a letter being consumed by the eye.
 *
 * @param t Progress from 0 (at text position) to 1 (at center)
 * @param startAngle Starting angle in radians (determines spiral entry direction)
 * @param startRadius Distance from center at t=0
 * @param rotations Number of spiral loops (1-2 feels cinematic)
 * @return Offset relative to the eye center
 */
private fun spiralPosition(
    t: Float,
    startAngle: Float,
    startRadius: Float,
    rotations: Float,
): Offset {
    val currentRadius = startRadius * (1f - t)
    val currentAngle = startAngle + (t * rotations * 2f * PI.toFloat())
    return Offset(
        x = cos(currentAngle) * currentRadius,
        y = sin(currentAngle) * currentRadius * 0.45f, // flatten to match elliptical eye
    )
}
