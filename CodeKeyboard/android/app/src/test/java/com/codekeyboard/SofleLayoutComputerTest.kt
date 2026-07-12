package com.codekeyboard

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure JUnit tests for SofleLayoutComputer — V5 layout.
 * No Android runtime needed.
 *
 * V5 structure:
 *   Top row : 8 keys (Tab, Esc + 6 layer slots), full-width, no stagger
 *   Left    : 4 rows × 5 cols, stagger [0, .25, .5, .75, 1.0]
 *   Right   : 4 rows × 5 cols, stagger [1.0, .75, .5, .25, 0]
 */
class SofleLayoutComputerTest {

    private val density  = 1f
    private val computer = SofleLayoutComputer(density)
    private val screenW  = 1000

    // ── Key counts ────────────────────────────────────────────────────────────

    @Test fun `compute returns keys for base layer`() {
        assertTrue(computer.compute(screenW, "base").isNotEmpty())
    }

    @Test fun `unknown layer falls back to base`() {
        val base    = computer.compute(screenW, "base")
        val unknown = computer.compute(screenW, "nope")
        assertEquals(base.size, unknown.size)
    }

    @Test fun `all five layers produce keys`() {
        for (layer in listOf("base","lower","raise","adj","func")) {
            assertTrue("Layer $layer empty", computer.compute(screenW, layer).isNotEmpty())
        }
    }

    @Test fun `base layer key count is correct`() {
        // top row:  8 (Tab Esc ` ^ Ctrl Alt Cmd \)
        // left  :  5+5+5+5 = 20
        // right :  5+5+5+5 = 20  (Bksp counts, ADJ counts)
        // total : 48
        val keys = computer.compute(screenW, "base")
        assertEquals(48, keys.size)
    }

    // ── Split geometry ────────────────────────────────────────────────────────

    @Test fun `top row keys span full width`() {
        val keys   = computer.compute(screenW, "base")
        val topRow = keys.filter { it.rect.top < computer.padding + computer.keyHeight }
        assertEquals(8, topRow.size)

        // leftmost top key starts at padding
        assertEquals(computer.padding, topRow.minOf { it.rect.left }, 0.5f)
        // rightmost top key ends near screenW - padding
        assertEquals(screenW - computer.padding, topRow.maxOf { it.rect.right }, 1f)
    }

    @Test fun `top row keys are all at same Y`() {
        val keys   = computer.compute(screenW, "base")
        val topRow = keys.filter { it.rect.top < computer.padding + computer.keyHeight }
        val tops   = topRow.map { it.rect.top }.distinct()
        assertEquals("All top-row keys should share the same Y", 1, tops.size)
        assertEquals(computer.padding, tops[0], 0.5f)
    }

    @Test fun `main rows start below top row`() {
        val keys      = computer.compute(screenW, "base")
        val mainY     = computer.padding + computer.keyHeight + computer.rowGap
        val mainKeys  = keys.filter { it.rect.top >= mainY - 0.5f }
        assertTrue("Main rows should have keys", mainKeys.isNotEmpty())
    }

    @Test fun `two halves separated by gap`() {
        val keys     = computer.compute(screenW, "base")
        val gap      = computer.halfGap(screenW)
        val hw       = (screenW - 2 * computer.padding - gap) / 2f
        val leftMax  = computer.padding + hw
        val rightMin = leftMax + gap

        val leftKeys  = keys.filter { it.rect.right  <= leftMax  + 1f && it.rect.top >= computer.padding + computer.keyHeight }
        val rightKeys = keys.filter { it.rect.left   >= rightMin - 1f }

        assertTrue("Left half should have keys",  leftKeys.isNotEmpty())
        assertTrue("Right half should have keys", rightKeys.isNotEmpty())

        val actualGap = rightKeys.minOf { it.rect.left } - leftKeys.maxOf { it.rect.right }
        assertTrue("Gap between halves should be >= halfGap", actualGap >= gap - 1f)
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
            assertTrue("${pk.key.label} left < 0",       pk.rect.left  >= 0f)
            assertTrue("${pk.key.label} right > $screenW", pk.rect.right <= screenW + 1f)
        }
    }

    @Test fun `all keys fit within computed height`() {
        val keys   = computer.compute(screenW, "base")
        val height = computer.heightPx(screenW).toFloat()
        for (pk in keys) {
            assertTrue(
                "${pk.key.label} bottom=${pk.rect.bottom} > height=$height",
                pk.rect.bottom <= height + 1f
            )
        }
    }

    // ── Stagger ───────────────────────────────────────────────────────────────

    @Test fun `left col 0 main keys have zero stagger offset`() {
        val keys    = computer.compute(screenW, "base")
        val mainY   = computer.padding + computer.keyHeight + computer.rowGap
        // q is left half, col 0, row 0 — should be at mainY exactly
        val q = keys.first { it.key.label == "q" }
        assertEquals(mainY, q.rect.top, 0.5f)
    }

    @Test fun `left half main row 0 columns progressively lower`() {
        val keys  = computer.compute(screenW, "base")
        // Row 0 left: q(0) w(1) e(2) r(3) t(4)
        val row0L = listOf("q","w","e","r","t").map { lbl ->
            keys.first { it.key.label == lbl }
        }
        for (i in 0 until row0L.size - 1) {
            assertTrue(
                "col$i.top=${row0L[i].rect.top} should be <= col${i+1}.top=${row0L[i+1].rect.top}",
                row0L[i].rect.top <= row0L[i+1].rect.top + 0.5f
            )
        }
    }

    @Test fun `right col 0 has maximum stagger (lowest position in its row)`() {
        val keys    = computer.compute(screenW, "base")
        val mainY   = computer.padding + computer.keyHeight + computer.rowGap
        // 'y' is right half col 0, row 0 — stagger = 1.0
        val y = keys.first { it.key.label == "y" }
        val expected = mainY + computer.keyHeight
        assertEquals(expected, y.rect.top, 0.5f)
    }

    @Test fun `right half main row 0 columns progressively higher`() {
        val keys  = computer.compute(screenW, "base")
        // Row 0 right: y(1.0) u(.75) i(.5) o(.25) p(0)
        val row0R = listOf("y","u","i","o","p").map { lbl ->
            keys.first { it.key.label == lbl }
        }
        for (i in 0 until row0R.size - 1) {
            assertTrue(
                "col$i.top=${row0R[i].rect.top} should be >= col${i+1}.top=${row0R[i+1].rect.top}",
                row0R[i].rect.top >= row0R[i+1].rect.top - 0.5f
            )
        }
    }

    // ── Hit-test ──────────────────────────────────────────────────────────────

    @Test fun `Tab key is in top row and hittable at its centre`() {
        val keys = computer.compute(screenW, "base")
        val tab  = keys.first { it.key.label == "Tab" }
        // Tab should be in the top row (y = padding)
        assertEquals(computer.padding, tab.rect.top, 0.5f)
        val hit = keys.firstOrNull { it.rect.contains(tab.rect.centerX, tab.rect.centerY) }
        assertEquals("Tab", hit?.key?.label)
    }

    @Test fun `Bksp key is hittable at its centre`() {
        val keys = computer.compute(screenW, "base")
        val bksp = keys.first { it.key.action == "backspace" }
        val hit  = keys.firstOrNull { it.rect.contains(bksp.rect.centerX, bksp.rect.centerY) }
        assertEquals("backspace", hit?.key?.action)
    }

    @Test fun `gap between halves returns no hit`() {
        val keys = computer.compute(screenW, "base")
        val gapX = screenW / 2f
        val gapY = computer.padding + computer.keyHeight + computer.rowGap + computer.keyHeight / 2f
        val hit  = keys.firstOrNull { it.rect.contains(gapX, gapY) }
        assertNull("Gap should not hit any key", hit)
    }

    @Test fun `snap radius does not bridge the full half-gap`() {
        // Gap between halves = halfGap(screenW) = ~50px at screenW=1000, density=1.
        // Half of that is ~25px from each edge to gap centre.
        // SNAP_RADIUS_DP = 8dp = 8px at density=1.
        // Gap centre is 25px from each half edge — well beyond 8dp snap radius.
        // Verifies the nearest-neighbour algorithm can't accidentally cross the gap.
        val keys  = computer.compute(screenW, "base")
        val gap   = computer.halfGap(screenW)
        val hw    = (screenW - 2 * computer.padding - gap) / 2f
        val leftMax  = computer.padding + hw
        val deepGapX = leftMax + gap / 2f   // dead centre of the gap
        val gapY  = computer.padding + computer.keyHeight + computer.rowGap + computer.keyHeight / 2f
        val exactHit = keys.firstOrNull { it.rect.contains(deepGapX, gapY) }
        assertNull("Centre of gap must not contain any key rect", exactHit)
        val nearestDist = keys.minOf { pk ->
            val dx = maxOf(pk.rect.left - deepGapX, 0f, deepGapX - pk.rect.right)
            val dy = maxOf(pk.rect.top  - gapY,     0f, gapY     - pk.rect.bottom)
            Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }
        assertTrue(
            "Nearest key from gap centre ($nearestDist px) must be > 8dp snap radius",
            nearestDist > 8f
        )
    }

    @Test fun `gap edge is within snap radius of adjacent key`() {
        // A touch 1px outside the left half's rightmost key must be within 8dp snap.
        // The rightmost left column (col 4) has stagger 1.0, so we sample at its centre Y.
        val keys     = computer.compute(screenW, "base")
        val gap      = computer.halfGap(screenW)
        val hw       = (screenW - 2 * computer.padding - gap) / 2f
        val leftMax  = computer.padding + hw
        val justOutsideX = leftMax + 1f

        val mainTopY  = computer.padding + computer.keyHeight + computer.rowGap
        val col4TopY  = mainTopY + computer.staggerLeft[4] * computer.keyHeight
        val col4MidY  = col4TopY + computer.keyHeight / 2f

        val nearestDist = keys.minOf { pk ->
            val dx = maxOf(pk.rect.left - justOutsideX, 0f, justOutsideX - pk.rect.right)
            val dy = maxOf(pk.rect.top  - col4MidY,     0f, col4MidY     - pk.rect.bottom)
            Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }
        assertTrue(
            "A touch 1px into the gap ($nearestDist px from nearest key) should be within 8dp snap",
            nearestDist <= 8f
        )
    }

    // ── Snap radius safety across densities ───────────────────────────────────

    @Test fun `maxSafeSnapPx is less than half-gap at density 1`() {
        // density=1: screenW=1000, halfGap=50px, half of that=25px
        // maxSafeSnapPx must be < 25px so it cannot bridge the gap
        val snap = computer.maxSafeSnapPx(screenW)
        val halfGapCentre = computer.halfGap(screenW) / 2f
        assertTrue("snap ($snap) must be < half-gap centre ($halfGapCentre)", snap < halfGapCentre)
    }

    @Test fun `maxSafeSnapPx is less than half-gap at density 3`() {
        // density=3 (typical modern Android): screenW=1080px physical
        val c = SofleLayoutComputer(3f)
        val snap = c.maxSafeSnapPx(1080)
        val halfGapCentre = c.halfGap(1080) / 2f
        assertTrue("snap ($snap) must be < half-gap centre ($halfGapCentre)", snap < halfGapCentre)
    }

    @Test fun `maxSafeSnapPx is less than half-gap at density 2 small screen`() {
        // density=2, screenW=720px: smallest common configuration
        val c = SofleLayoutComputer(2f)
        val snap = c.maxSafeSnapPx(720)
        val halfGapCentre = c.halfGap(720) / 2f
        assertTrue("snap ($snap) must be < half-gap centre ($halfGapCentre)", snap < halfGapCentre)
    }

    @Test fun `maxSafeSnapPx never exceeds 8dp`() {
        // 8dp cap holds regardless of screen size or density
        for ((w, d) in listOf(720 to 2f, 1080 to 3f, 1440 to 3.5f, 2560 to 2f)) {
            val c    = SofleLayoutComputer(d)
            val snap = c.maxSafeSnapPx(w)
            val capPx = 8f * d
            assertTrue("snap ($snap) must be <= 8dp (${capPx}px) at density=$d screenW=$w", snap <= capPx)
        }
    }

    @Test fun `maxSafeSnapPx matches the hardcoded 8dp constant at typical screen sizes`() {
        // At typical phone widths the geometric safe bound exceeds 8dp,
        // so the cap of 8dp is what actually applies. This confirms the
        // constant in NativeKeyboardView is not arbitrary — it equals maxSafeSnapPx.
        for ((w, d) in listOf(1080 to 3f, 1440 to 3.5f)) {
            val c       = SofleLayoutComputer(d)
            val snap    = c.maxSafeSnapPx(w)
            val expected = 8f * d
            assertEquals("maxSafeSnapPx should equal 8dp cap at w=$w d=$d", expected, snap, 0.5f)
        }
    }

    // ── Height ────────────────────────────────────────────────────────────────

    @Test fun `heightPx is stable across calls`() {
        assertEquals(computer.heightPx(screenW), computer.heightPx(screenW))
    }

    @Test fun `heightPx scales with density`() {
        val h1 = SofleLayoutComputer(1f).heightPx(screenW)
        val h2 = SofleLayoutComputer(2f).heightPx(screenW)
        assertTrue(h2 > h1)
    }
}
