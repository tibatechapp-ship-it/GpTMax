package dev.gptmax.app

import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceUtf8Test {
    @Test
    fun readsValidUtf8WithinLimit() {
        val source = "Hello العربية 👋"
        val bytes = source.toByteArray(Charsets.UTF_8)
        assertEquals(source, readWorkspaceUtf8Bounded(ByteArrayInputStream(bytes), bytes.size))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInputLargerThanByteLimit() {
        readWorkspaceUtf8Bounded(ByteArrayInputStream(ByteArray(9)), 8)
    }

    @Test(expected = CharacterCodingException::class)
    fun rejectsMalformedUtf8() {
        readWorkspaceUtf8Bounded(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)), 8)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeLimit() {
        readWorkspaceUtf8Bounded(ByteArrayInputStream(byteArrayOf()), -1)
    }
}
