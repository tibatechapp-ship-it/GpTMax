package com.tibatech.gptmax.data.repo

import java.security.MessageDigest

/**
 * Fix #2 (code review): content fingerprints used by the workspace write
 * path. Every read-modify-write captures the on-disk SHA-256 before the
 * mutation and verifies the readback afterwards, so a silently corrupted
 * or clobbered write is detected instead of lost.
 *
 * Pure JVM: works on Android and in unit tests.
 */
object Fingerprint {

    fun sha256(content: String): String = sha256(content.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
