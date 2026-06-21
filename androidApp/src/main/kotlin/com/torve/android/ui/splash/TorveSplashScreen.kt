package com.torve.android.ui.splash

import android.media.MediaPlayer
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseOut
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.android.R
import kotlinx.coroutines.delay

// Animation phase constants (milliseconds)
private const val SOUND_HEAD_START = 500L
private const val AMBIENT_START = 300L
private const val ORBIT1_START = 200L
private const val ORBIT2_START = 500L
private const val ORBIT3_START = 800L
private const val IRIS_START = 1000L
private const val DIAMOND_START = 1300L
private const val VOID_START = 1500L
private const val CORE_START = 1600L
private const val SPECULAR_START = 1700L
private const val SCAN_START = 1800L
private const val TEXT_START = 2000L
private const val ZOOM_START = 2800L
private const val ZOOM_DURATION = 1400L
private const val TOTAL_DURATION = ZOOM_START + ZOOM_DURATION + 200L

// Colors
private val AmberMain = Color(0xFFD4A03C)
private val AmberHot = Color(0xFFECC44E)
private val AmberDeep = Color(0xFFB87A22)
private val Void = Color(0xFF050507)

@Composable
fun TorveSplashScreen(
    onSplashComplete: () -> Unit,
) {
    val context = LocalContext.current
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var isZooming by remember { mutableStateOf(false) }

    // Play dark sting audio — sound starts 500ms BEFORE the animation
    DisposableEffect(Unit) {
        val mediaPlayer = try {
            MediaPlayer.create(context, R.raw.torve_sting_dark)?.apply {
                setVolume(0.85f, 0.85f)
                start()
            }
        } catch (_: Exception) { null }

        onDispose {
            mediaPlayer?.release()
        }
    }

    // Animation timer — starts after sound head start
    LaunchedEffect(Unit) {
        delay(SOUND_HEAD_START)
        val startTime = System.currentTimeMillis()
        while (true) {
            elapsedMs = System.currentTimeMillis() - startTime
            if (elapsedMs >= ZOOM_START && !isZooming) {
                isZooming = true
            }
            if (elapsedMs >= TOTAL_DURATION) {
                onSplashComplete()
                break
            }
            delay(16) // ~60fps
        }
    }

    // Orbit rotation (continuous)
    val infiniteTransition = rememberInfiniteTransition(label = "orbits")
    val orbit1Angle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "o1",
    )
    val orbit2Angle by infiniteTransition.animateFloat(
        initialValue = 120f, targetValue = 480f,
        animationSpec = infiniteRepeatable(tween(4500, easing = LinearEasing)), label = "o2",
    )
    val orbit3Angle by infiniteTransition.animateFloat(
        initialValue = 240f, targetValue = 600f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "o3",
    )

    // Zoom animation
    val zoomScale by animateFloatAsState(
        targetValue = if (isZooming) 12f else 1f,
        animationSpec = tween(
            durationMillis = ZOOM_DURATION.toInt(),
            easing = CubicBezierEasing(0.45f, 0f, 0.15f, 1f),
        ),
        label = "zoom",
    )
    val zoomAlpha by animateFloatAsState(
        targetValue = if (isZooming) 0f else 1f,
        animationSpec = tween(
            durationMillis = (ZOOM_DURATION * 0.7f).toInt(),
            delayMillis = (ZOOM_DURATION * 0.4f).toInt(),
            easing = EaseOut,
        ),
        label = "zoomAlpha",
    )

    // Helper: compute element opacity based on timing
    fun elementAlpha(startMs: Long, fadeInMs: Long = 400L): Float {
        if (elapsedMs < startMs) return 0f
        val progress = ((elapsedMs - startMs).toFloat() / fadeInMs).coerceIn(0f, 1f)
        return progress
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Void)
            .alpha(zoomAlpha),
        contentAlignment = Alignment.Center,
    ) {
        // The eye
        Canvas(
            modifier = Modifier.size(240.dp),
        ) {
            val cx = size.width / 2
            val cy = size.height / 2
            val zoomPivot = Offset(cx, cy)

            // Draw all vector elements in draw scope with scale so zoom stays crisp.
            scale(zoomScale, zoomScale, pivot = zoomPivot) {
                // 1. Ambient glow
                val ambientAlpha = elementAlpha(AMBIENT_START, 1500L)
                if (ambientAlpha > 0f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(AmberMain.copy(alpha = 0.06f * ambientAlpha), Color.Transparent),
                            center = Offset(cx, cy),
                            radius = size.width * 0.8f,
                        ),
                        radius = size.width * 0.8f,
                        center = Offset(cx, cy),
                    )
                }

                // 2. Orbiting elliptical rings (dashed stroke)
                fun drawOrbitRing(
                    rx: Float, ry: Float, tiltDeg: Float,
                    rotationDeg: Float, startMs: Long, dashOn: Float, dashOff: Float,
                    strokeW: Float = 1.5f, alphaMax: Float = 1f,
                ) {
                    val alpha = elementAlpha(startMs, 800L) * alphaMax
                    if (alpha <= 0f) return

                    // When zooming, rings shrink
                    val ringScale = if (isZooming) {
                        val zoomProgress = ((elapsedMs - ZOOM_START).toFloat() / 800f).coerceIn(0f, 1f)
                        1f - zoomProgress
                    } else 1f

                    if (ringScale <= 0.01f) return

                    // When zooming, rings spin faster
                    val speedMult = if (isZooming) {
                        val zoomProgress = ((elapsedMs - ZOOM_START).toFloat() / 800f).coerceIn(0f, 1f)
                        1f + zoomProgress * 8f
                    } else 1f

                    rotate(tiltDeg + rotationDeg * speedMult, pivot = Offset(cx, cy)) {
                        drawOval(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    AmberHot.copy(alpha = 0.7f * alpha),
                                    AmberDeep.copy(alpha = 0.3f * alpha),
                                ),
                            ),
                            topLeft = Offset(cx - rx * ringScale, cy - ry * ringScale),
                            size = Size(rx * 2 * ringScale, ry * 2 * ringScale),
                            style = Stroke(
                                width = strokeW,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashOn, dashOff)),
                            ),
                        )
                    }
                }

                val dp1 = 1.dp.toPx()
                drawOrbitRing(95f * dp1 / 2, 52f * dp1 / 2, 15f, orbit1Angle, ORBIT1_START, 8f, 12f)
                drawOrbitRing(78f * dp1 / 2, 44f * dp1 / 2, -30f, orbit2Angle, ORBIT2_START, 6f, 10f)
                drawOrbitRing(62f * dp1 / 2, 36f * dp1 / 2, 60f, orbit3Angle, ORBIT3_START, 4f, 8f)

                // 3. Iris ring
                val irisAlpha = elementAlpha(IRIS_START, 600L) * 0.4f
                if (irisAlpha > 0f) {
                    drawCircle(
                        color = AmberHot.copy(alpha = irisAlpha),
                        radius = 42f * dp1 / 2,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.5f),
                    )
                }

                // 4. Diamond pupil
                val diamondAlpha = elementAlpha(DIAMOND_START, 500L)
                if (diamondAlpha > 0f) {
                    val diamondScale = 0.3f + 0.7f * diamondAlpha
                    val diamondRotation = 90f * (1f - diamondAlpha)
                    val diamondR = 38f * dp1 / 2 * diamondScale

                    rotate(diamondRotation, pivot = Offset(cx, cy)) {
                        val diamondPath = Path().apply {
                            moveTo(cx, cy - diamondR)
                            lineTo(cx + diamondR, cy)
                            lineTo(cx, cy + diamondR)
                            lineTo(cx - diamondR, cy)
                            close()
                        }
                        drawPath(
                            path = diamondPath,
                            brush = Brush.linearGradient(
                                colors = listOf(AmberHot, AmberDeep),
                                start = Offset(cx - diamondR, cy - diamondR),
                                end = Offset(cx + diamondR, cy + diamondR),
                            ),
                            alpha = diamondAlpha * 0.92f,
                        )
                    }
                }

                // 5. Void center
                val voidAlpha = elementAlpha(VOID_START, 400L)
                if (voidAlpha > 0f) {
                    drawCircle(color = Void, radius = 10f * dp1 / 2, center = Offset(cx, cy), alpha = voidAlpha)
                }

                // 6. Inner core
                val coreAlpha = elementAlpha(CORE_START, 300L)
                if (coreAlpha > 0f) {
                    val coreScale = coreAlpha
                    drawCircle(
                        brush = Brush.linearGradient(listOf(AmberHot, AmberMain)),
                        radius = 4.5f * dp1 / 2 * coreScale,
                        center = Offset(cx, cy),
                        alpha = coreAlpha * 0.9f,
                    )
                }

                // 7. Specular highlights
                val specAlpha = elementAlpha(SPECULAR_START, 300L)
                if (specAlpha > 0f) {
                    drawCircle(
                        color = AmberHot.copy(alpha = 0.5f * specAlpha),
                        radius = 6f * dp1 / 2,
                        center = Offset(cx + 13 * dp1, cy - 16 * dp1),
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.6f * specAlpha),
                        radius = 2f * dp1 / 2,
                        center = Offset(cx + 15 * dp1, cy - 18 * dp1),
                    )
                }

                // 8. Scan lines
                val scanAlpha = elementAlpha(SCAN_START, 400L) * 0.2f
                if (scanAlpha > 0f) {
                    val scanPaint = AmberHot.copy(alpha = scanAlpha)
                    drawLine(scanPaint, Offset(cx - 100 * dp1, cy), Offset(cx - 72 * dp1, cy), strokeWidth = 0.8f)
                    drawLine(scanPaint, Offset(cx + 72 * dp1, cy), Offset(cx + 100 * dp1, cy), strokeWidth = 0.8f)
                }
            }
        }

        // 9. "TORVE" wordmark below the eye
        val textAlpha = elementAlpha(TEXT_START, 800L)
        if (textAlpha > 0f) {
            val textOffset = (1f - textAlpha) * 10f
            Text(
                text = "TORVE",
                color = Color(0xFF55555C).copy(alpha = textAlpha),
                fontSize = 13.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (80 + textOffset).dp)
                    .alpha(if (isZooming) 0f else textAlpha),
            )
        }
    }
}
