package com.torve.android.player

import androidx.media3.exoplayer.Renderer
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleDelayRendererTest {

    @Test
    fun positiveDelayMovesSubtitleClockLaterAndNegativeDelayMovesItEarlier() {
        assertEquals(8_000_000L, subtitleRendererPositionUs(10_000_000L, 2_000))
        assertEquals(12_000_000L, subtitleRendererPositionUs(10_000_000L, -2_000))
        assertEquals(0L, subtitleRendererPositionUs(500_000L, 2_000))
    }

    @Test
    fun rendererAppliesCurrentDelayToRenderAndResetWithoutRecreatingDelegate() {
        val renderedPositions = mutableListOf<Long>()
        val resetPositions = mutableListOf<Long>()
        val delegate = Proxy.newProxyInstance(
            Renderer::class.java.classLoader,
            arrayOf(Renderer::class.java),
        ) { _, method, args ->
            when (method.name) {
                "render" -> renderedPositions += args[0] as Long
                "resetPosition" -> resetPositions += args[0] as Long
            }
            primitiveDefault(method.returnType)
        } as Renderer
        val delayState = SubtitleDelayState()
        val renderer = SubtitleDelayRenderer(delegate, delayState)

        delayState.delayMs = 1_500
        renderer.render(8_000_000L, 0L)
        renderer.resetPosition(8_000_000L)
        delayState.delayMs = -750
        renderer.render(8_000_000L, 0L)

        assertEquals(listOf(6_500_000L, 8_750_000L), renderedPositions)
        assertEquals(listOf(6_500_000L), resetPositions)
    }

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }
}
