package dev.gptmax.app.agent

/** Strict small JSON reader for the model's action envelope; no reflection or permissive coercion. */
internal object AgentActionParser {
    fun parse(raw: String, step: Int): AgentModelAction {
        val json = extractObject(raw.trim())
        val root = JsonObjectReader(json).readObject()
        return when (root.string("type")) {
            "final" -> {
                val text = root.string("text").trim()
                require(text.isNotEmpty()) { "Final answer is empty." }
                AgentModelAction.FinalAnswer(text)
            }
            "tool" -> {
                val name = root.string("name")
                val args = root.objectValue("arguments").mapValues { (_, value) ->
                    value as? String ?: throw IllegalArgumentException("Tool arguments must be strings.")
                }
                val id = root.optionalString("id")?.takeIf { it.isNotBlank() } ?: "agent-step-$step"
                AgentModelAction.CallTool(AgentToolCall(id, name, args))
            }
            else -> throw IllegalArgumentException("Model output must be a JSON object with type 'tool' or 'final'.")
        }
    }


    private fun extractObject(raw: String): String {
        val start = raw.indexOf('{')
        require(start >= 0) { "No JSON action object found." }
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until raw.length) {
            val ch = raw[index]
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\\\') escaped = true
                else if (ch == '"') inString = false
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return raw.substring(start, index + 1)
                    }
                }
            }
        }
        throw IllegalArgumentException("Incomplete JSON action object.")
    }

    private class JsonObjectReader(private val source: String) {
        private var index = 0

        fun readObject(requireEndAfter: Boolean = true): Map<String, Any> {
            skipWhitespace()
            expect('{')
            val result = linkedMapOf<String, Any>()
            skipWhitespace()
            if (peek('}')) {
                index++
                if (requireEndAfter) requireEnd()
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val value: Any = when {
                    peek('"') -> readString()
                    peek('{') -> readObject(requireEndAfter = false)
                    else -> throw IllegalArgumentException("Only string values and nested objects are accepted.")
                }
                require(result.put(key, value) == null) { "Duplicate JSON key: $key" }
                skipWhitespace()
                if (peek('}')) {
                    index++
                    break
                }
                expect(',')
            }
            if (requireEndAfter) requireEnd()
            return result
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (index < source.length) {
                val ch = source[index++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(index < source.length) { "Incomplete escape." }
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(index + 4 <= source.length) { "Incomplete unicode escape." }
                                val code = source.substring(index, index + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Invalid unicode escape.")
                                out.append(code.toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("Invalid JSON escape.")
                        }
                    }
                    else -> {
                        require(ch.code >= 0x20) { "Control character in JSON string." }
                        out.append(ch)
                    }
                }
            }
            throw IllegalArgumentException("Unterminated JSON string.")
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in charArrayOf(' ', '\n', '\r', '\t')) index++
        }

        private fun expect(ch: Char) {
            require(index < source.length && source[index] == ch) { "Expected '$ch' at JSON offset $index." }
            index++
        }

        private fun peek(ch: Char): Boolean = index < source.length && source[index] == ch

        private fun requireEnd() {
            skipWhitespace()
            require(index == source.length) { "Unexpected trailing JSON data." }
        }
    }

    private fun Map<String, Any>.string(key: String): String =
        this[key] as? String ?: throw IllegalArgumentException("Missing or invalid '$key'.")

    private fun Map<String, Any>.optionalString(key: String): String? = this[key] as? String

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.objectValue(key: String): Map<String, Any> =
        this[key] as? Map<String, Any> ?: throw IllegalArgumentException("Missing or invalid '$key'.")
}
