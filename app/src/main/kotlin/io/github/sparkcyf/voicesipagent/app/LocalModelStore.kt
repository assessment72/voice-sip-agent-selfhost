package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class InstalledLocalModel(
    val id: String,
    val file: File,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * Installs user-selected model files into private app storage while offline.
 * It never downloads models and never accepts a path supplied by the caller.
 */
class LocalModelStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val modelDirectory = File(context.applicationContext.filesDir, "local-ai/models")

    fun import(
        uri: Uri,
        modelId: String,
        expectedSha256: String,
        maxBytes: Long,
    ): InstalledLocalModel = importWithExtension(uri, modelId, expectedSha256, maxBytes, "model")

    /** Imports a local GGUF LLM file for llama.cpp; it is never fetched from the network. */
    fun importGguf(
        uri: Uri,
        modelId: String,
        expectedSha256: String,
        maxBytes: Long,
    ): InstalledLocalModel = importWithExtension(uri, modelId, expectedSha256, maxBytes, "gguf")

    private fun importWithExtension(
        uri: Uri,
        modelId: String,
        expectedSha256: String,
        maxBytes: Long,
        extension: String,
    ): InstalledLocalModel {
        require(modelId.matches(Regex("[a-zA-Z0-9._-]{1,80}"))) { "Invalid model ID" }
        require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "A SHA-256 checksum is required" }
        require(maxBytes > 0) { "Maximum model size must be positive" }
        val input = resolver.openInputStream(uri) ?: error("Cannot open selected model file")
        return input.use {
            VerifiedModelInstaller.install(
                input = it,
                directory = modelDirectory,
                fileName = "$modelId.$extension",
                expectedSha256 = expectedSha256.lowercase(Locale.ROOT),
                maxBytes = maxBytes,
            )
        }.let { InstalledLocalModel(modelId, it.file, it.sha256, it.sizeBytes) }
    }

    fun installedModels(): List<File> =
        modelDirectory.listFiles()?.filter {
            it.isFile && (it.name.endsWith(".model") || it.name.endsWith(".gguf"))
        }?.sortedBy { it.name } ?: emptyList()
}

internal object VerifiedModelInstaller {
    data class Result(val file: File, val sha256: String, val sizeBytes: Long)

    fun install(
        input: InputStream,
        directory: File,
        fileName: String,
        expectedSha256: String,
        maxBytes: Long,
    ): Result {
        require(fileName.matches(Regex("[a-zA-Z0-9._-]{1,80}\\.(model|gguf)")))
        require(expectedSha256.matches(Regex("[a-f0-9]{64}")))
        require(maxBytes > 0)
        check(directory.exists() || directory.mkdirs()) { "Cannot create private model directory" }

        val destination = File(directory, fileName).canonicalFile
        check(destination.parentFile == directory.canonicalFile) { "Model path escaped private directory" }
        val temporary = File(directory, ".$fileName.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L

        try {
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    require(total <= maxBytes) { "Model exceeds the configured size limit" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual == expectedSha256) { "Model SHA-256 mismatch" }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return Result(destination, actual, total)
        } catch (t: Throwable) {
            temporary.delete()
            throw t
        }
    }
}
