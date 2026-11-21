package os.flow.launcher

import java.util.Locale

enum class CallActionKind { ANSWER, DECLINE }

private val ANSWER_TOKENS = listOf("repondre", "decrocher", "accepter", "answer", "accept")
private val DECLINE_TOKENS = listOf(
    "refuser", "raccrocher", "rejeter", "terminer", "ignorer",
    "decline", "reject", "hang up", "hangup", "end call", "dismiss", "end",
)

/** Reconnaît le rôle d'un bouton de notification d'appel (FR / EN). */
fun classifyCallAction(label: String): CallActionKind? {
    val norm = label.fold().trim().lowercase(Locale.ROOT)
    if (norm.isEmpty()) return null
    if (ANSWER_TOKENS.any { norm == it || norm.startsWith("$it ") || norm.contains(it) }) {
        return CallActionKind.ANSWER
    }
    if (DECLINE_TOKENS.any { norm == it || norm.startsWith("$it ") || norm.contains(it) }) {
        return CallActionKind.DECLINE
    }
    return null
}
