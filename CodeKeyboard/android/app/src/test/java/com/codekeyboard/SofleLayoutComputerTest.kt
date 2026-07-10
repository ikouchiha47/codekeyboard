package com.codekeyboard

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure JUnit tests for SofleLayoutComputer.
 * No Android runtime needed — SofleLayoutComputer has zero Android imports.
 */
class SofleLayoutComputerTest {

    // Use density=1f so px == dp, making assertions easy to reason about.
    private val density  = 1f
    private val computer = SofleLayoutComputer(density)
    private val screenW  = 1000

    // ── Structural tests ──────────────────────────────────────────────────────

    @Test fun `compute returns keys for base layer`() {
        val keys = computer.compute(screenW, "base")
        assertTrue("Should have keys", keys.isNotEmpty())
    }

    @Test fun `compute falls back to base for unknown layer`() {
        val base    = computer.compute(screenW, "base")
        val unknown = computer.compute(screenW, "does_not_exist")
        assertEquals(base.size, unknown.size)
    }

    @Test fun `all five layers produce keys`() {
        for (layer in listOf("base", "lower", "raise", "adj", "func")) {
            val keys = computer.compute(screenW, layer)
            assertTrue("Layer $layer should have keys", keys.isNotEmpty())
        }
    }

    @Test fun `base layer has correct key count`() {
        // Left: 6+6+6+5 = 23 non-empty keys, Right: 7+7+7+7 = 28  → total 51
        val keys = computer.compute(screenW, "base")
        assertEquals(51, keys.size)
    }

    // ── Split geometry tests ───────────────────────────────────────────────────

    @Test fun `keys span both halves with a gap`() {
        val keys    = computer.compute(screenW, "base")
        val midX    = screenW / 2f
        val leftMax = keys.filter { it.rect.right  <= midX }.maxOfOrNull { it.rect.right }
        val rightMin= keys.filter { it.rect.left   >= midX }.minOfOrNull { it.rect.left }

        assertNotNull("Should have left-half keys",  leftMax)
        assertNotNull("Should have right-half keys", rightMin)
        assertTrue(
            "Gap should exist between halves (halfGap=${computer.halfGap}px)",
            rightMin!! - leftMax!! >= computer.halfGap - 1f   // 1px tolerance
        )
    }

    @Test fun `no two keys overlap`() {
        val keys = computer.compute(screenW, "base")
        for (i in keys.indices) {
            for (j in i + 1 until keys.size) {
                val a = keys[i].rect
                val b = keys[j].rect
                val overlaps = a.left < b.right && a.right > b.left &&
                               a.top  < b.bottom && a.bottom > b.top
                assertFalse(
                    "Keys '${keys[i].key.label}' and '${keys[j].key.label}' overlap",
                    overlaps
                )
            }
        }
    }

    @Test fun `all keys fit within screen width`() {
        val keys = computer.compute(screenW, "base")
        for (pk in keys) {
            assertTrue("Key '${pk.key.label}' left=${pk.rect.left} < 0", pk.rect.left >= 0f)
            assertTrue("Key '${pk.key.label}' right=${pk.rect.right} > $screenW", pk.rect.right <= screenW + 1f)
        }
    }

    @Test fun `all keys fit within computed height`() {
        val keys   = computer.compute(screenW, "base")
        val height = computer.heightPx(screenW).toFloat()
        for (pk in keys) {
            assertTrue(
                "Key '${pk.key.label}' bottom=${pk.rect.bottom} > height=$height",
                pk.rect.bottom <= height + 1f
            )
        }
    }

    // ── Stagger tests ─────────────────────────────────────────────────────────

    @Test fun `left half column 0 has zero stagger offset`() {
        val keys = computer.compute(screenW, "base")
        // Tab is left half, col 0, row 0 — its top should be at padding exactly
        val tab = keys.first { it.key.label == "Tab" }
        assertEquals(computer.padding, tab.rect.top, 0.5f)
    }

    @Test fun `left half columns are progressively lower`() {
        // Row 0: Tab(col0), q(col1), w(col2), e(col3), r(col4), t(col5)
        val keys  = computer.compute(screenW, "base")
        val row0L = listOf("Tab","q","w","e","r","t").map { label ->
            keys.first { it.key.label == label }
        }
        // Each successive column top must be >= previous top (or equal for col4==col5)
        for (i in 0 until row0L.size - 1) {
            assertTrue(
                "col$i top=${row0L[i].rect.top} should be <= col${i+1} top=${row0L[i+1].rect.top}",
                row0L[i].rect.top <= row0L[i + 1].rect.top + 0.5f
            )
        }
    }

    @Test fun `right half column 0 has maximum stagger (lowest position)`() {
        val keys  = computer.compute(screenW, "base")
        // 'y' is right half col 0, row 0 — stagger = 1.0 → top = padding + keyHeight
        val y = keys.first { it.key.label == "y" }
        val expectedTop = computer.padding + computer.keyHeight
        assertEquals(expectedTop, y.rect.top, 0.5f)
    }

    @Test fun `right half columns are progressively higher (stagger decreases)`() {
        val keys  = computer.compute(screenW, "base")
        // Row 0 right: y(col0=1.0), u(col1=0.75), i(col2=0.5), o(col3=0.25), p(col4=0), [(col5=0), ](col6=0)
        val row0R = listOf("y","u","i","o","p","[","]").map { label ->
            keys.first { it.key.label == label }
        }
        for (i in 0 until row0R.size - 1) {
            assertTrue(
                "col$i top=${row0R[i].rect.top} should be >= col${i+1} top=${row0R[i+1].rect.top}",
                row0R[i].rect.top >= row0R[i + 1].rect.top - 0.5f
            )
        }
    }

    // ── Hit-test tests ────────────────────────────────────────────────────────

    @Test fun `hitTest finds Tab key at its center`() {
        val keys = computer.compute(screenW, "base")
        val tab  = keys.first { it.key.label == "Tab" }
        val found = keys.firstOrNull { it.rect.contains(tab.rect.centerX, tab.rect.centerY) }
        assertEquals("Tab", found?.key?.label)
    }

    @Test fun `hitTest finds Bksp key at its center`() {
        val keys  = computer.compute(screenW, "base")
        val bksp  = keys.first { it.key.action == "backspace" }
        val found = keys.firstOrNull { it.rect.contains(bksp.rect.centerX, bksp.rect.centerY) }
        assertEquals("backspace", found?.key?.action)
    }

    @Test fun `hitTest returns null for gap between halves`() {
        val keys = computer.compute(screenW, "base")
        // Point in the middle of the gap
        val gapX  = screenW / 2f
        val gapY  = computer.padding + computer.keyHeight / 2f
        val found = keys.firstOrNull { it.rect.contains(gapX, gapY) }
        assertNull("Gap should not hit any key", found)
    }

    // ── heightPx test ─────────────────────────────────────────────────────────

    @Test fun `heightPx is consistent across calls`() {
        assertEquals(computer.heightPx(screenW), computer.heightPx(screenW))
    }

    @Test fun `heightPx grows with larger density`() {
        val h1 = SofleLayoutComputer(1f).heightPx(screenW)
        val h2 = SofleLayoutComputer(2f).heightPx(screenW)
        assertTrue("Higher density should produce taller keyboard", h2 > h1)
    }
}
