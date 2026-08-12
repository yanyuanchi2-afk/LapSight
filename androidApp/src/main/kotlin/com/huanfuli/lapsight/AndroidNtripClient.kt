package com.huanfuli.lapsight

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.huanfuli.lapsight.shared.NtripConnectionPhase
import com.huanfuli.lapsight.shared.NtripConnectionState
import com.huanfuli.lapsight.shared.NtripSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Streams NTRIP RTCM corrections from the phone network to the BLE HUD. */
class AndroidNtripClient(
    private val latestGga: () -> String?,
    private val onCorrections: (ByteArray) -> Unit,
    private val onState: (NtripConnectionState) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamJob: Job? = null
    @Volatile private var settings = NtripSettings()
    @Volatile private var hudConnected = false

    @Synchronized
    fun apply(settings: NtripSettings) {
        this.settings = settings
        restart()
    }

    @Synchronized
    fun setHudConnected(connected: Boolean) {
        if (hudConnected == connected) return
        hudConnected = connected
        restart()
    }

    @Synchronized
    fun close() {
        streamJob?.cancel()
        streamJob = null
        scope.cancel()
    }

    @Synchronized
    private fun restart() {
        streamJob?.cancel()
        streamJob = null
        val activeSettings = settings
        when {
            !activeSettings.enabled -> emit(NtripConnectionPhase.Disabled)
            !activeSettings.isComplete -> emit(
                NtripConnectionPhase.IncompleteConfiguration,
                message = "Host, port and mountpoint are required",
            )
            !hudConnected -> emit(NtripConnectionPhase.WaitingForHud)
            else -> streamJob = scope.launch { reconnectingStream(activeSettings) }
        }
    }

    private suspend fun reconnectingStream(activeSettings: NtripSettings) {
        while (currentCoroutineContext().isActive && hudConnected && settings == activeSettings) {
            try {
                streamOnce(activeSettings)
            } catch (error: Exception) {
                if (!currentCoroutineContext().isActive) return
                emit(NtripConnectionPhase.Failed, message = error.message ?: "NTRIP connection failed")
            }
            delay(RECONNECT_DELAY_MILLIS)
        }
    }

    private suspend fun streamOnce(activeSettings: NtripSettings) {
        emit(NtripConnectionPhase.Connecting)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(activeSettings.host.trim(), activeSettings.port), CONNECT_TIMEOUT_MILLIS)
            socket.soTimeout = READ_POLL_TIMEOUT_MILLIS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            output.write(buildNtripRequest(activeSettings))
            output.flush()
            readSuccessfulNtripResponse(input)

            var received = 0L
            var forwarded = 0L
            var lastDataAt = System.currentTimeMillis()
            var lastGgaAt = 0L
            emit(NtripConnectionPhase.Streaming)
            val buffer = ByteArray(NTRIP_READ_BYTES)
            while (currentCoroutineContext().isActive && hudConnected &&
                settings == activeSettings && !socket.isClosed
            ) {
                val now = System.currentTimeMillis()
                if (now - lastGgaAt >= GGA_INTERVAL_MILLIS) {
                    latestGga()?.trim()?.takeIf { it.isNotEmpty() }?.let { sentence ->
                        output.write(sentence.encodeToByteArray())
                        output.write(CRLF)
                        output.flush()
                        lastGgaAt = now
                    }
                }
                val count = try {
                    input.read(buffer)
                } catch (_: SocketTimeoutException) {
                    if (now - lastDataAt > DATA_TIMEOUT_MILLIS) {
                        throw IOException("NTRIP correction stream timed out")
                    }
                    continue
                }
                if (count < 0) throw IOException("NTRIP caster closed the stream")
                if (count == 0) continue
                lastDataAt = System.currentTimeMillis()
                val correction = buffer.copyOf(count)
                received += count
                onCorrections(correction)
                forwarded += count
                emit(
                    phase = NtripConnectionPhase.Streaming,
                    receivedBytes = received,
                    forwardedBytes = forwarded,
                )
            }
        }
    }

    private fun emit(
        phase: NtripConnectionPhase,
        receivedBytes: Long = 0L,
        forwardedBytes: Long = 0L,
        message: String? = null,
    ) {
        onState(NtripConnectionState(phase, receivedBytes, forwardedBytes, message))
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_POLL_TIMEOUT_MILLIS = 2_000
        const val DATA_TIMEOUT_MILLIS = 20_000L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val GGA_INTERVAL_MILLIS = 5_000L
        const val NTRIP_READ_BYTES = 2_048
        val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    }
}

internal fun buildNtripRequest(settings: NtripSettings): ByteArray {
    val mountpoint = settings.mountpoint.trim().trimStart('/')
    val authorization = if (settings.username.isNotEmpty() || settings.password.isNotEmpty()) {
        val credentials = "${settings.username}:${settings.password}"
        val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
        "Authorization: Basic $encoded\r\n"
    } else {
        ""
    }
    return buildString {
        append("GET /$mountpoint HTTP/1.0\r\n")
        append("Host: ${settings.host}:${settings.port}\r\n")
        append("User-Agent: NTRIP LapSight/1.0\r\n")
        append("Ntrip-Version: Ntrip/2.0\r\n")
        append(authorization)
        append("Connection: close\r\n\r\n")
    }.toByteArray(StandardCharsets.US_ASCII)
}

internal fun readSuccessfulNtripResponse(input: BufferedInputStream) {
    val firstLine = readAsciiLine(input)
    val success = firstLine.startsWith("ICY 200", ignoreCase = true) ||
        firstLine.startsWith("HTTP/1.0 200", ignoreCase = true) ||
        firstLine.startsWith("HTTP/1.1 200", ignoreCase = true)
    if (!success) throw IOException("NTRIP caster rejected request: $firstLine")
    if (!firstLine.startsWith("ICY", ignoreCase = true)) {
        while (true) {
            if (readAsciiLine(input).isEmpty()) break
        }
    }
}

private fun readAsciiLine(input: BufferedInputStream): String {
    val bytes = ArrayList<Byte>()
    while (bytes.size < MAX_NTRIP_HEADER_LINE_BYTES) {
        val value = input.read()
        if (value < 0) throw IOException("NTRIP caster closed during handshake")
        if (value == '\n'.code) break
        if (value != '\r'.code) bytes += value.toByte()
    }
    if (bytes.size >= MAX_NTRIP_HEADER_LINE_BYTES) throw IOException("NTRIP response header is too long")
    return bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
}

private const val MAX_NTRIP_HEADER_LINE_BYTES = 2_048

class AndroidNtripSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("ntrip_settings", Context.MODE_PRIVATE)
    private val secretBox = AndroidNtripSecretBox()

    fun load(): NtripSettings = NtripSettings(
        enabled = preferences.getBoolean("enabled", false),
        host = preferences.getString("host", "").orEmpty(),
        port = preferences.getInt("port", 2101),
        mountpoint = preferences.getString("mountpoint", "").orEmpty(),
        username = preferences.getString("username", "").orEmpty(),
        password = preferences.getString("password_encrypted", null)
            ?.let(secretBox::decrypt)
            .orEmpty(),
    )

    fun save(settings: NtripSettings) {
        preferences.edit()
            .putBoolean("enabled", settings.enabled)
            .putString("host", settings.host.trim())
            .putInt("port", settings.port)
            .putString("mountpoint", settings.mountpoint.trim().trimStart('/'))
            .putString("username", settings.username)
            .putString("password_encrypted", secretBox.encrypt(settings.password))
            .remove("password")
            .apply()
    }
}

private class AndroidNtripSecretBox {
    fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    fun decrypt(value: String): String? = if (value.isEmpty()) {
        ""
    } else {
        runCatching {
            val payload = Base64.getDecoder().decode(value)
            require(payload.size > GCM_IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(GCM_TAG_BITS, payload.copyOfRange(0, GCM_IV_BYTES)),
            )
            cipher.doFinal(payload.copyOfRange(GCM_IV_BYTES, payload.size))
                .toString(StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "LapSightNtripCredentialsV1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
