package com.olivier.gpxroad.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mêmes cas que `ColorFlavorPatcherTests` iOS. */
class ColorFlavorPatcherTest {
    private fun parse(s: String) = ColorFlavorPatcher.parseColor(s)!!
    private fun transform(s: String, f: MapColorFlavor) = parse(ColorFlavorPatcher.transformedColorString(s, f)!!)

    @Test
    fun parsesAllColorForms() {
        parse("#ff0000").let { assertEquals(0.0, it.h, 0.5); assertEquals(1.0, it.s, 0.01); assertEquals(0.5, it.l, 0.01) }
        assertEquals(120.0, parse("#0f0").h, 0.5)
        assertEquals(0.8, parse("rgba(247, 239, 195, 0.8)").a, 0.01)
        parse("hsl(36,6%,74%)").let { assertEquals(36.0, it.h, 0.01); assertEquals(0.06, it.s, 0.001); assertEquals(0.74, it.l, 0.001) }
        parse("hsla(98,61%,72%,0.7)").let { assertEquals(98.0, it.h, 0.01); assertEquals(0.7, it.a, 0.01) }
        listOf("interpolate", "Noto Sans Regular", "some-icon-name", "zoom").forEach { assertNull(ColorFlavorPatcher.parseColor(it)) }
    }

    @Test
    fun flavorsBehaveLikeIOS() {
        val original = parse("#3366cc")
        transform("#3366cc", MapColorFlavor.STANDARD).let { assertEquals(original.h, it.h, 0.1); assertEquals(original.s, it.s, 0.01); assertEquals(original.l, it.l, 0.01) }
        transform("hsl(350,50%,50%)", MapColorFlavor.EARTHY).let { assertEquals(12.0, it.h, 0.5) }
        transform("hsl(200,40%,20%)", MapColorFlavor.HIGH_CONTRAST).let { assertTrue(it.s > 0.4); assertEquals(0.13, it.l, 0.01) }
        transform("hsl(200,40%,80%)", MapColorFlavor.HIGH_CONTRAST).let { assertEquals(0.73, it.l, 0.01) }
        assertTrue(transform("hsl(60,4%,95%)", MapColorFlavor.HIGH_CONTRAST).s > 0.15)
        transform("#f8f4f0", MapColorFlavor.HIGH_CONTRAST).let { assertTrue(it.l < 1.0); assertTrue(it.s > 0.3) }
        assertEquals("hsla(12.0, 65.0%, 45.0%, 1.000)", ColorFlavorPatcher.transformedColorString("hsl(350,50%,50%)", MapColorFlavor.EARTHY))
        assertEquals("hsla(0.0, 0.0%, 43.2%, 0.500)", ColorFlavorPatcher.transformedColorString("rgba(128,128,128,0.5)", MapColorFlavor.HIGH_CONTRAST))
    }
}
