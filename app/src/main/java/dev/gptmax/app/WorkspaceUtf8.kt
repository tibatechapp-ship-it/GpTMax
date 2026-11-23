package dev.gptmax.app

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal fun readWorkspaceUtf8Bounded(input: InputStream, maxBytes: Int): String {
    require(maxBytes >= 0) { "Maximum byte count must be non-negative." }
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "File exceeds configured editor limit." }
        output.write(buffer, 0, count)
    }
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
}
