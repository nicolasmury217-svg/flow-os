package os.flow.launcher

/** Élément posé sur une page d'accueil : une appli seule ou un dossier d'applis. */
sealed interface HomeSlot {
    val slotId: String

    data class App(val key: String) : HomeSlot {
        override val slotId: String get() = key
    }

    data class Folder(val id: String, val name: String, val keys: List<String>) : HomeSlot {
        override val slotId: String get() = "folder:$id"
    }
}

private const val FOLDER_PREFIX = "folder:"

/** Relit la disposition sauvegardée (compatible avec l'ancien format d'une clé par ligne). */
fun parseHomeSlots(raw: String): List<HomeSlot> =
    raw.split("\n").mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        if (!trimmed.startsWith(FOLDER_PREFIX)) return@mapNotNull HomeSlot.App(trimmed)
        val parts = trimmed.removePrefix(FOLDER_PREFIX).split("|")
        if (parts.size < 3) return@mapNotNull null
        val id = parts[0].ifBlank { return@mapNotNull null }
        val name = parts[1].ifBlank { "Dossier" }
        val keys = parts[2].split(",").filter { it.isNotBlank() }.distinct()
        when (keys.size) {
            0 -> null
            1 -> HomeSlot.App(keys.first())
            else -> HomeSlot.Folder(id, name, keys)
        }
    }

fun serializeHomeSlots(slots: List<HomeSlot>): String =
    slots.joinToString("\n") { slot ->
        when (slot) {
            is HomeSlot.App -> slot.key
            is HomeSlot.Folder -> "$FOLDER_PREFIX${slot.id}|${sanitizeFolderName(slot.name)}|${slot.keys.joinToString(",")}"
        }
    }

fun sanitizeFolderName(raw: String): String =
    raw.replace("|", " ").replace("\n", " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "Dossier" }

/** Toutes les clés d'applis présentes sur l'accueil (seules ou dans des dossiers). */
fun allSlotKeys(slots: List<HomeSlot>): Set<String> =
    slots.flatMap { slot ->
        when (slot) {
            is HomeSlot.App -> listOf(slot.key)
            is HomeSlot.Folder -> slot.keys
        }
    }.toSet()

/** Retire les applis désinstallées et dissout tout dossier réduit à 1 seule appli. */
fun pruneHomeSlots(slots: List<HomeSlot>, known: Set<String>): List<HomeSlot> =
    slots.mapNotNull { slot ->
        when (slot) {
            is HomeSlot.App -> slot.takeIf { it.key in known }
            is HomeSlot.Folder -> {
                val kept = slot.keys.filter { it in known }
                when (kept.size) {
                    0 -> null
                    1 -> HomeSlot.App(kept.first())
                    else -> slot.copy(keys = kept)
                }
            }
        }
    }

/** Retire une clé d'appli de l'accueil (qu'elle soit seule ou dans un dossier). */
fun removeKeyFromSlots(slots: List<HomeSlot>, key: String): List<HomeSlot> =
    slots.mapNotNull { slot ->
        when (slot) {
            is HomeSlot.App -> slot.takeIf { it.key != key }
            is HomeSlot.Folder -> {
                val kept = slot.keys.filter { it != key }
                when (kept.size) {
                    0 -> null
                    1 -> HomeSlot.App(kept.first())
                    else -> slot.copy(keys = kept)
                }
            }
        }
    }

/** Regroupe [sourceKey] avec l'élément à [targetIndex] (crée un dossier ou ajoute au dossier cible). */
fun mergeAppsIntoFolder(
    slots: List<HomeSlot>,
    sourceKey: String,
    targetIndex: Int,
    folderId: String,
    defaultName: String = "Dossier",
): List<HomeSlot> {
    val target = slots.getOrNull(targetIndex) ?: return slots
    if (target is HomeSlot.App && target.key == sourceKey) return slots
    if (target is HomeSlot.Folder && sourceKey in target.keys) return slots
    val cleaned = removeKeyFromSlots(slots, sourceKey)
    val newIdx = cleaned.indexOfFirst { it.slotId == target.slotId }
    if (newIdx == -1) return cleaned
    val merged = when (val t = cleaned[newIdx]) {
        is HomeSlot.App -> HomeSlot.Folder(folderId, defaultName, listOf(t.key, sourceKey))
        is HomeSlot.Folder -> t.copy(keys = t.keys + sourceKey)
    }
    return cleaned.toMutableList().apply { set(newIdx, merged) }
}

/** Ajoute [appKey] dans le dossier [folderId] (et la retire de sa position précédente). */
fun addAppToFolder(slots: List<HomeSlot>, appKey: String, folderId: String): List<HomeSlot> {
    val cleaned = removeKeyFromSlots(slots, appKey)
    return cleaned.map { slot ->
        if (slot is HomeSlot.Folder && slot.id == folderId && appKey !in slot.keys) {
            slot.copy(keys = slot.keys + appKey)
        } else {
            slot
        }
    }
}

/** Retire [appKey] du dossier [folderId] (le dossier se dissout automatiquement s'il n'en reste qu'une). */
fun removeAppFromFolder(slots: List<HomeSlot>, folderId: String, appKey: String): List<HomeSlot> =
    slots.mapNotNull { slot ->
        if (slot !is HomeSlot.Folder || slot.id != folderId) return@mapNotNull slot
        val kept = slot.keys.filter { it != appKey }
        when (kept.size) {
            0 -> null
            1 -> HomeSlot.App(kept.first())
            else -> slot.copy(keys = kept)
        }
    }

fun renameFolder(slots: List<HomeSlot>, folderId: String, newName: String): List<HomeSlot> =
    slots.map { slot ->
        if (slot is HomeSlot.Folder && slot.id == folderId) slot.copy(name = sanitizeFolderName(newName))
        else slot
    }

fun dissolveFolder(slots: List<HomeSlot>, folderId: String): List<HomeSlot> =
    slots.flatMap { slot ->
        if (slot is HomeSlot.Folder && slot.id == folderId) slot.keys.map { HomeSlot.App(it) }
        else listOf(slot)
    }

private val AI_PACKAGES = setOf(
    "com.anthropic.claude",
    "com.openai.chatgpt",
    "com.google.android.apps.bard",
    "dev.lovable.build",
    "tech.butterfly.app",
    "ai.perplexity.app.android",
    "com.microsoft.copilot",
    "ai.x.grok",
    "com.deepseek.chat",
    "ai.mistral.lechat",
)

private val EXTRA_GOOGLE_PACKAGES = setOf(
    "com.android.chrome",
    "com.android.vending",
)

fun packageOfKey(key: String): String = key.substringBefore("/")

fun isAiPackage(pkg: String): Boolean = pkg in AI_PACKAGES

fun isGooglePackage(pkg: String): Boolean =
    !isAiPackage(pkg) && (pkg.startsWith("com.google.android.") || pkg in EXTRA_GOOGLE_PACKAGES)

/**
 * Regroupe toutes les applis Google dans un dossier « Google » et toutes les applis IA dans un
 * dossier « IA » en tête de l'accueil, sans déplacer les applis présentes dans le dock.
 */
fun groupGoogleAndAi(
    slots: List<HomeSlot>,
    dockKeys: List<String>,
    allKnownKeys: List<String>,
): List<HomeSlot> {
    val dockSet = dockKeys.toSet()
    val googleKeys = allKnownKeys.filter { it !in dockSet && isGooglePackage(packageOfKey(it)) }
    val aiKeys = allKnownKeys.filter { it !in dockSet && isAiPackage(packageOfKey(it)) }
    val moved = (googleKeys + aiKeys).toSet()

    var remaining = slots
    for (k in moved) {
        remaining = removeKeyFromSlots(remaining, k)
    }
    remaining = remaining.filterNot { it is HomeSlot.Folder && (it.id == "google" || it.id == "ia") }

    val prefix = mutableListOf<HomeSlot>()
    when (googleKeys.size) {
        0 -> Unit
        1 -> prefix += HomeSlot.App(googleKeys.first())
        else -> prefix += HomeSlot.Folder("google", "Google", googleKeys)
    }
    when (aiKeys.size) {
        0 -> Unit
        1 -> prefix += HomeSlot.App(aiKeys.first())
        else -> prefix += HomeSlot.Folder("ia", "IA", aiKeys)
    }
    return prefix + remaining
}
