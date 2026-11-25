package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class VerifiedModelInstallerTest {
    @Test fun installsModelOnlyWhenChecksumMatches() {
        val directory = Files.createTempDirectory("local-model-test").toFile()
        val bytes = "offline-model".toByteArray()
        try {
            val result = VerifiedModelInstaller.install(
                ByteArrayInputStream(bytes), directory, "asr.model", sha256(bytes), 1024,
            )
            assertTrue(result.file.isFile)
            assertEquals(bytes.size.toLong(), result.sizeBytes)
            assertEquals(sha256(bytes), result.sha256)
            assertEquals("offline-model", result.file.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun installsGgufModelWithVerifiedChecksum() {
        val directory = Files.createTempDirectory("local-gguf-test").toFile()
        val bytes = "gguf-offline-model".toByteArray()
        try {
            val result = VerifiedModelInstaller.install(
                ByteArrayInputStream(bytes), directory, "assistant.gguf", sha256(bytes), 1024,
            )
            assertTrue(result.file.isFile)
            assertEquals("assistant.gguf", result.file.name)
            assertEquals(sha256(bytes), result.sha256)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun rejectsChecksumMismatchAndRemovesPartialFile() {
        val directory = Files.createTempDirectory("local-model-test").toFile()
        try {
            try {
                VerifiedModelInstaller.install(
                    ByteArrayInputStream("wrong".toByteArray()), directory, "llm.model", "0".repeat(64), 1024,
                )
                throw AssertionError("Expected checksum rejection")
            } catch (_: IllegalArgumentException) {
                assertFalse(File(directory, "llm.model").exists())
                assertFalse(File(directory, ".llm.model.part").exists())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun rejectsOversizedModel() {
        val directory = Files.createTempDirectory("local-model-test").toFile()
        val bytes = ByteArray(32) { 1 }
        try {
            try {
                VerifiedModelInstaller.install(
                    ByteArrayInputStream(bytes), directory, "tts.model", sha256(bytes), 8,
                )
                throw AssertionError("Expected size rejection")
            } catch (_: IllegalArgumentException) {
                assertFalse(File(directory, "tts.model").exists())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
