package os.flow.launcher

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/**
 * Évaluateur arithmétique léger (+, -, *, /, parenthèses) pour la recherche instantanée du tiroir.
 * Renvoie null si la saisie n'est pas une expression valide contenant au moins un opérateur.
 */
fun evaluateQuickMath(input: String): String? {
    val clean = input
        .replace(',', '.')
        .replace('×', '*')
        .replace('÷', '/')
        .replace('−', '-')
        .replace(" ", "")
    if (clean.length < 3 || clean.length > 64) return null
    if (!clean.any { it in "+-*/" }) return null
    if (!clean.all { it in "0123456789.+-*/()" }) return null

    val parser = ExprParser(clean)
    val value = parser.parse() ?: return null
    if (!parser.isAtEnd() || value.isNaN() || value.isInfinite()) return null

    val rounded = if (abs(value - value.toLong()) < 1e-9) {
        value.toLong().toString()
    } else {
        DecimalFormat("#.####", DecimalFormatSymbols(Locale.FRENCH)).format(value)
    }
    return rounded
}

private class ExprParser(private val s: String) {
    private var i = 0

    fun isAtEnd(): Boolean = i == s.length

    fun parse(): Double? = parseAddSub()

    private fun parseAddSub(): Double? {
        var left = parseMulDiv() ?: return null
        while (i < s.length && (s[i] == '+' || s[i] == '-')) {
            val op = s[i++]
            val right = parseMulDiv() ?: return null
            left = if (op == '+') left + right else left - right
        }
        return left
    }

    private fun parseMulDiv(): Double? {
        var left = parseUnary() ?: return null
        while (i < s.length && (s[i] == '*' || s[i] == '/')) {
            val op = s[i++]
            val right = parseUnary() ?: return null
            if (op == '/' && abs(right) < 1e-12) return null
            left = if (op == '*') left * right else left / right
        }
        return left
    }

    private fun parseUnary(): Double? = when {
        i < s.length && s[i] == '+' -> { i++; parseUnary() }
        i < s.length && s[i] == '-' -> { i++; parseUnary()?.let { -it } }
        else -> parsePrimary()
    }

    private fun parsePrimary(): Double? {
        if (i >= s.length) return null
        if (s[i] == '(') {
            i++
            val inner = parseAddSub() ?: return null
            if (i >= s.length || s[i] != ')') return null
            i++
            return inner
        }
        val start = i
        var dots = 0
        while (i < s.length && (s[i].isDigit() || s[i] == '.')) {
            if (s[i] == '.') dots++
            i++
        }
        if (i == start || dots > 1) return null
        return s.substring(start, i).toDoubleOrNull()
    }
}
