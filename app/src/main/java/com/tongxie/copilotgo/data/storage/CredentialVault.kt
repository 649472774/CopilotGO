package com.tongxie.copilotgo.data.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretVault {
    suspend fun read(name: String): String?
    suspend fun write(name: String, value: String)
}

/** Encrypted, non-backup namespaces; missing keys are errors, never a reset signal. */
class CredentialVault(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : SecretVault {
    private val directory = File(context.applicationContext.noBackupFilesDir, "credentials")

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked(name) }
    }

    override suspend fun write(name: String, value: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = fileFor(name)
            val plain = value.toByteArray(Charsets.UTF_8)
            require(plain.size <= MAX_BYTES) { "凭据超过存储大小限制" }
            val key = key(create = true)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            check(cipher.iv.size == IV_BYTES)
            val encoded = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
            AtomicFiles.write(file, encoded)
            check(readLocked(name) == value) { "加密凭据写入校验失败" }
        }
    }

    private fun readLocked(name: String): String? {
        val file = fileFor(name)
        if (!file.exists()) return null
        val bytes = AtomicFiles.read(file, MAX_BYTES + IV_BYTES + 17)
        if (bytes.size < 1 + IV_BYTES + 16 || bytes[0] != 1.toByte()) {
            throw IOException("加密凭据格式损坏，原文件已保留")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, key(create = false),
            GCMParameterSpec(128, bytes.copyOfRange(1, 1 + IV_BYTES))
        )
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes, 1 + IV_BYTES, bytes.size - 1 - IV_BYTES)
            .toString(Charsets.UTF_8)
    }

    private fun fileFor(name: String): File {
        require(name.matches(Regex("[a-z][a-z0-9_-]{0,63}"))) { "无效的凭据命名空间" }
        return File(directory, "$name.enc")
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(keyAlias)) {
            return store.getKey(keyAlias, null) as? SecretKey
                ?: throw IOException("凭据密钥不可用，原文件已保留")
        }
        val encryptedFiles = directory.listFiles { file -> file.extension == "enc" }
        if (directory.exists() && encryptedFiles == null) {
            throw IOException("无法读取加密凭据目录，未创建新密钥")
        }
        if (!create || !encryptedFiles.isNullOrEmpty()) {
            throw IOException("凭据密钥丢失，原文件已保留")
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    companion object {
        private val mutex = Mutex()
        private const val DEFAULT_KEY_ALIAS = "copilotgo.credentials.v1"
        private const val IV_BYTES = 12
        private const val MAX_BYTES = 64 * 1024
    }
}
