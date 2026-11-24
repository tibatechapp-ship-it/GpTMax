package com.tibatech.gptmax.core.guardrails

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review #1/#2 follow-ups: committed templates stay readable, a sensitive
 * parent directory always wins over an exempt file name, and irreversible
 * key/credential material is protected from writes and deletes.
 */
class SensitivePathsPolicyTest {

    @Test
    fun `committed templates stay readable`() {
        assertFalse(SensitivePaths.isSensitive(".env.example"))
        assertFalse(SensitivePaths.isSensitive("config/.env.template"))
        assertFalse(SensitivePaths.isSensitive(".env.sample"))
        assertFalse(SensitivePaths.isSensitive(".env.test"))
    }

    @Test
    fun `sensitive parent wins over exempt file name`() {
        assertTrue(SensitivePaths.isSensitive(".ssh/.env.example"))
        assertTrue(SensitivePaths.isSensitive("secrets/.env.template"))
    }

    @Test
    fun `key material is protected from writes`() {
        assertTrue(SensitivePaths.isProtectedFromWrite("keys/id_rsa"))
        assertTrue(SensitivePaths.isProtectedFromWrite("release.keystore"))
        assertTrue(SensitivePaths.isProtectedFromWrite("certs/server.pem"))
        assertTrue(SensitivePaths.isProtectedFromWrite("credentials.json"))
        assertTrue(SensitivePaths.isProtectedFromWrite(".ssh/id_ed25519"))
        assertFalse(SensitivePaths.isProtectedFromWrite("src/App.kt"))
        assertFalse(SensitivePaths.isProtectedFromWrite(".env.example"))
    }

    @Test
    fun `existing behaviour is preserved`() {
        assertTrue(SensitivePaths.isSensitive(".aws/credentials"))
        assertTrue(SensitivePaths.isSensitive(".ssh/id_rsa"))
        assertTrue(SensitivePaths.isSensitive("config/.env"))
        assertTrue(SensitivePaths.isSensitive("app/release.keystore"))
        assertFalse(SensitivePaths.isSensitive("src/App.kt"))
        assertFalse(SensitivePaths.isSensitive("ui/CredentialsScreen.kt"))
    }
}
