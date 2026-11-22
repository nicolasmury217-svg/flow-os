package os.flow.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandTimeTest {
    @Test
    fun formatsElapsedAndRemainingMediaTime() {
        assertEquals("0:00", formatDurationShort(0))
        assertEquals("0:08", formatDurationShort(8_000))
        assertEquals("2:57", formatDurationShort(177_000))
        assertEquals("-1:32", formatRemainingShort(posMs = 177_000, durationMs = 269_000))
        assertEquals("-0:00", formatRemainingShort(posMs = 300_000, durationMs = 269_000))
    }

    @Test
    fun clampsProgressFraction() {
        assertEquals(0f, progressFraction(0, 100_000), 1e-4f)
        assertEquals(0.5f, progressFraction(50_000, 100_000), 1e-4f)
        assertEquals(1f, progressFraction(120_000, 100_000), 1e-4f)
        assertEquals(0f, progressFraction(50_000, 0), 1e-4f)
    }
}
