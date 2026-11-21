package os.flow.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallParserTest {
    @Test
    fun detectsFrenchAnswerAndDeclineLabels() {
        assertEquals(CallActionKind.ANSWER, classifyCallAction("Répondre"))
        assertEquals(CallActionKind.ANSWER, classifyCallAction("Décrocher"))
        assertEquals(CallActionKind.ANSWER, classifyCallAction("Accepter"))
        assertEquals(CallActionKind.DECLINE, classifyCallAction("Refuser"))
        assertEquals(CallActionKind.DECLINE, classifyCallAction("Raccrocher"))
        assertEquals(CallActionKind.DECLINE, classifyCallAction("Rejeter"))
    }

    @Test
    fun detectsEnglishAnswerAndHangupLabels() {
        assertEquals(CallActionKind.ANSWER, classifyCallAction("Answer"))
        assertEquals(CallActionKind.ANSWER, classifyCallAction("Accept"))
        assertEquals(CallActionKind.DECLINE, classifyCallAction("Decline"))
        assertEquals(CallActionKind.DECLINE, classifyCallAction("Hang up"))
        assertEquals(CallActionKind.DECLINE, classifyCallAction("End call"))
    }

    @Test
    fun ignoresNonCallActions() {
        assertNull(classifyCallAction("Haut-parleur"))
        assertNull(classifyCallAction("Muet"))
        assertNull(classifyCallAction("Speaker"))
    }
}
