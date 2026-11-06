package com.spotifyautoplaylist.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/** Size → tier; values are AppWidgetManager OPTION_APPWIDGET_* dp (approximate phone launcher cells). */
class WidgetLayoutTierTest {
    @Test fun `compact covers one-row strips and narrow two-by-two`() {
        assertEquals(WidgetLayoutTier.COMPACT, WidgetLayoutTier.select(110, 40))   // 2×1
        assertEquals(WidgetLayoutTier.COMPACT, WidgetLayoutTier.select(320, 60))   // 4×1
        assertEquals(WidgetLayoutTier.COMPACT, WidgetLayoutTier.select(160, 160))  // 2×2
        assertEquals(WidgetLayoutTier.COMPACT, WidgetLayoutTier.select(250, 129))
    }

    @Test fun `medium is the default four-by-two band`() {
        assertEquals(WidgetLayoutTier.MEDIUM, WidgetLayoutTier.select(220, 130))
        assertEquals(WidgetLayoutTier.MEDIUM, WidgetLayoutTier.select(250, 130))   // legacy 4×2 (73n-16)
        assertEquals(WidgetLayoutTier.MEDIUM, WidgetLayoutTier.select(320, 180))   // modern 4×2
        assertEquals(WidgetLayoutTier.MEDIUM, WidgetLayoutTier.select(299, 300))
        assertEquals(WidgetLayoutTier.MEDIUM, WidgetLayoutTier.select(400, 239))
    }

    @Test fun `large when both dimensions are generous`() {
        assertEquals(WidgetLayoutTier.LARGE, WidgetLayoutTier.select(300, 240))
        assertEquals(WidgetLayoutTier.LARGE, WidgetLayoutTier.select(320, 260))    // 4×3
        assertEquals(WidgetLayoutTier.LARGE, WidgetLayoutTier.select(400, 360))    // 5×4
        assertEquals(WidgetLayoutTier.LARGE, WidgetLayoutTier.select(1000, 1000))
    }

    @Test fun `size map thresholds increase so the launcher picks the largest fitting tier`() {
        val tiers = WidgetLayoutTier.entries
        for (i in 1 until tiers.size) {
            assert(tiers[i].minWidthDp >= tiers[i - 1].minWidthDp && tiers[i].minHeightDp > tiers[i - 1].minHeightDp)
            assertEquals(tiers[i], WidgetLayoutTier.select(tiers[i].minWidthDp.toInt(), tiers[i].minHeightDp.toInt()))
        }
    }
}
