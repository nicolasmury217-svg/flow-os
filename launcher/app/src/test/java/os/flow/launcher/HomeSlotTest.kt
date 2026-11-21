package os.flow.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSlotTest {
    @Test
    fun parseLegacyPlainKeysAndFoldersRoundTrip() {
        val raw = listOf(
            "com.a/Main",
            "folder:f1|Réseaux|com.b/Main,com.c/Main",
            "com.d/Main",
        ).joinToString("\n")
        val slots = parseHomeSlots(raw)
        assertEquals(3, slots.size)
        assertEquals(HomeSlot.App("com.a/Main"), slots[0])
        assertEquals(HomeSlot.Folder("f1", "Réseaux", listOf("com.b/Main", "com.c/Main")), slots[1])
        assertEquals(HomeSlot.App("com.d/Main"), slots[2])
        assertEquals(raw, serializeHomeSlots(slots))
    }

    @Test
    fun syncSlotsRemovesUninstalledAndUnwrapsSingleAppFolder() {
        val slots = listOf(
            HomeSlot.Folder("f1", "Test", listOf("com.a/A", "com.b/B")),
            HomeSlot.App("com.c/C"),
        )
        val synced = pruneHomeSlots(slots, known = setOf("com.a/A", "com.c/C"))
        assertEquals(listOf(HomeSlot.App("com.a/A"), HomeSlot.App("com.c/C")), synced)
    }

    @Test
    fun groupIntoNewFolderMergesTwoApps() {
        val slots = listOf(HomeSlot.App("a"), HomeSlot.App("b"), HomeSlot.App("c"))
        val updated = mergeAppsIntoFolder(slots, sourceKey = "c", targetIndex = 0, folderId = "f1", defaultName = "Dossier")
        assertEquals(
            listOf(HomeSlot.Folder("f1", "Dossier", listOf("a", "c")), HomeSlot.App("b")),
            updated,
        )
    }

    @Test
    fun addAppToExistingFolderAppendsAndRemovesStandalone() {
        val slots = listOf(
            HomeSlot.Folder("f1", "Musique", listOf("a", "b")),
            HomeSlot.App("c"),
        )
        val updated = addAppToFolder(slots, appKey = "c", folderId = "f1")
        assertEquals(listOf(HomeSlot.Folder("f1", "Musique", listOf("a", "b", "c"))), updated)
    }

    @Test
    fun removeAppFromTwoItemFolderUnwrapsRemainingApp() {
        val slots = listOf(HomeSlot.Folder("f1", "Musique", listOf("a", "b")))
        val updated = removeAppFromFolder(slots, folderId = "f1", appKey = "a")
        assertEquals(listOf(HomeSlot.App("b")), updated)
    }

    @Test
    fun renameFolderSanitizesSeparators() {
        val slots = listOf(HomeSlot.Folder("f1", "Old", listOf("a", "b")))
        val updated = renameFolder(slots, "f1", "  Mes | Applis\n ")
        assertEquals("Mes Applis", (updated.first() as HomeSlot.Folder).name)
    }

    @Test
    fun dissolveFolderExpandsAppsInPlace() {
        val slots = listOf(
            HomeSlot.App("x"),
            HomeSlot.Folder("f1", "Outils", listOf("a", "b")),
            HomeSlot.App("y"),
        )
        val updated = dissolveFolder(slots, "f1")
        assertEquals(listOf("x", "a", "b", "y"), updated.map { (it as HomeSlot.App).key })
        assertTrue(updated.all { it is HomeSlot.App })
    }

    @Test
    fun groupGoogleAndAiCreatesTwoFoldersAndPreservesDock() {
        val allKeys = listOf(
            "com.android.chrome/Main",
            "com.google.android.gm/Gmail",
            "com.google.android.apps.maps/Maps",
            "com.google.android.apps.bard/Gemini",
            "com.anthropic.claude/Main",
            "com.openai.chatgpt/Main",
            "com.spotify.music/Main",
        )
        val slots = allKeys.filterNot { it == "com.android.chrome/Main" }.map { HomeSlot.App(it) }
        val dock = listOf("com.android.chrome/Main")
        val grouped = groupGoogleAndAi(slots, dock, allKeys)

        val google = grouped[0] as HomeSlot.Folder
        assertEquals("Google", google.name)
        assertEquals(
            listOf("com.google.android.gm/Gmail", "com.google.android.apps.maps/Maps"),
            google.keys,
        )

        val ia = grouped[1] as HomeSlot.Folder
        assertEquals("IA", ia.name)
        assertEquals(
            listOf("com.google.android.apps.bard/Gemini", "com.anthropic.claude/Main", "com.openai.chatgpt/Main"),
            ia.keys,
        )

        assertEquals(HomeSlot.App("com.spotify.music/Main"), grouped[2])
    }
}
