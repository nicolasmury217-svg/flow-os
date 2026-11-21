package os.flow.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickMathTest {
    @Test
    fun evaluatesBasicArithmeticAndPrecedence() {
        assertEquals("540", evaluateQuickMath("45*12"))
        assertEquals("14", evaluateQuickMath("2 + 3 * 4"))
        assertEquals("20", evaluateQuickMath("(2 + 3) * 4"))
        assertEquals("2,5", evaluateQuickMath("5 / 2"))
    }

    @Test
    fun supportsFrenchCommaAndUnicodeOperators() {
        assertEquals("6", evaluateQuickMath("2,5 + 3,5"))
        assertEquals("15", evaluateQuickMath("3 × 5"))
        assertEquals("4", evaluateQuickMath("12 ÷ 3"))
    }

    @Test
    fun ignoresPlainNumbersAndText() {
        assertNull(evaluateQuickMath("42"))
        assertNull(evaluateQuickMath("spotify"))
        assertNull(evaluateQuickMath("10 / 0"))
        assertNull(evaluateQuickMath("2 +"))
    }
}
