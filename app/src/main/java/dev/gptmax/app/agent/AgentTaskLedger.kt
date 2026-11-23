package dev.gptmax.app.agent

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Private append-only task metadata. It intentionally stores no prompt, raw file
 * content, command output, or credential values.
 */
class AgentTaskLedger(private val file: File) {
    @Synchronized
    fun record(
        sessionId: String,
        event: String,
        toolName: String? = null,
        status: String? = null,
        durationMs: Long? = null,
        output: String? = null,
    ) {
        require(sessionId.length <= 128 && event.length <= 64)
        if (file.exists() && file.length() > MAX_LEDGER_BYTES) {
            val rotated = File(file.parentFile, file.name + ".previous")
            if (rotated.exists()) rotated.delete()
            require(file.renameTo(rotated)) { "Could not rotate agent ledger." }
        }
        file.parentFile?.mkdirs()
        val row = linkedMapOf<String, String?>(
            "timestamp" to System.currentTimeMillis().toString(),
            "session_id" to sessionId,
            "event" to event,
            "tool" to toolName,
            "status" to status,
            "duration_ms" to durationMs?.toString(),
            "output_sha256" to output?.let(::sha256),
        )
        FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(row.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
                "\"${escape(key)}\":${value?.let { "\" ${escape(it)} \"" .replace(" ", "") } ?: "null"}"
            }
            writer.newLine()
        }
    }

    companion object {
        private const val MAX_LEDGER_BYTES = 2 * 1024 * 1024

        fun newSessionId(): String = UUID.randomUUID().toString()

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private fun escape(value: String): String = buildString {
            value.forEach { ch ->
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
        }
    }
}
