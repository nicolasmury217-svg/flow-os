package os.flow.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSearchTest {
    private val items = listOf(
        "Agenda",
        "Appareil photo",
        "Calculatrice",
        "Paramètres",
        "Smart Switch",
        "Téléphone",
    )

    @Test
    fun foldNormalizesCaseAndAccents() {
        assertEquals("parametres", "Paramètres".fold())
        assertEquals("telephone", "Téléphone".fold())
        assertEquals("ecole d'ete", "École d'Été".fold())
    }

    @Test
    fun emptyQueryReturnsAllItemsInOrder() {
        assertEquals(items, filterAndRankApps(items, "   ") { it.fold() })
    }

    @Test
    fun wordPrefixRanksBeforeSubstringMatch() {
        val result = filterAndRankApps(items, "par") { it.fold() }
        // "Paramètres" (préfixe de mot) avant "Appareil photo" (sous-chaîne)
        assertEquals(listOf("Paramètres", "Appareil photo"), result)
    }

    @Test
    fun secondWordPrefixRanksBeforeSubstring() {
        val sample = listOf("Appareil photo", "Photoshop", "Mon appareil")
        val result = filterAndRankApps(sample, "pho") { it.fold() }
        assertEquals(listOf("Appareil photo", "Photoshop"), result)
    }

    @Test
    fun multiWordPrefixMatchesFullLabelStart() {
        val result = filterAndRankApps(items, "app pho") { it.fold() }
        assertEquals(emptyList<String>(), result)

        val exactStart = filterAndRankApps(items, "appareil ph") { it.fold() }
        assertEquals(listOf("Appareil photo"), exactStart)
    }

    @Test
    fun diacriticsInQueryAreFolded() {
        val result = filterAndRankApps(items, "télé") { it.fold() }
        assertEquals(listOf("Téléphone"), result)
    }

    @Test
    fun hyphenatedWordsMatchSecondTokenPrefix() {
        val sample = listOf("Wi-Fi", "Fichiers")
        val result = filterAndRankApps(sample, "fi") { it.fold() }
        assertEquals(listOf("Wi-Fi", "Fichiers"), result)
    }

    @Test
    fun noMatchReturnsEmptyList() {
        val result = filterAndRankApps(items, "zzzz") { it.fold() }
        assertEquals(emptyList<String>(), result)
    }
}
