package it.smartplugmonitor

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TuyaClient(
    private val deviceId: String,
    private val ipAddress: String,
    private val localKey: String
) {

    companion object {
        private const val PORT = 6668

        private const val CMD_SESSION_START = 0x03
        private const val CMD_SESSION_RESPONSE = 0x04
        private const val CMD_SESSION_FINISH = 0x05
        private const val CMD_DP_QUERY_NEW = 0x10

        private val PREFIX = byteArrayOf(0x00, 0x00, 0x66, 0x99)
        private val SUFFIX = byteArrayOf(0x00, 0x00, 0x99, 0x66)
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    private val secureRandom = SecureRandom()

    private val realKey: ByteArray
        get() = localKey.toByteArray(Charsets.UTF_8)

    private var sessionKey: ByteArray? = null

    private var sequence = 1

    @Synchronized
    fun getPower(): Double {

        ensureConnected()

        val payload = JSONObject()
            .put("data", JSONObject().put("dps", JSONObject()))
            .toString()
            .toByteArray(Charsets.UTF_8)

        sendMessage(
            CMD_DP_QUERY_NEW,
            payload,
            sessionKey ?: throw Exception("Sessione Tuya non disponibile")
        )

        val response = readMessage()

        val plain = decryptFrame(
            response,
            sessionKey ?: throw Exception("Sessione Tuya non disponibile")
        )

        return extractPower(plain)
    }

    private fun ensureConnected() {
        if (socket?.isConnected == true && socket?.isClosed == false) {
            return
        }

        close()

        socket = Socket()
        socket!!.connect(InetSocketAddress(ipAddress, PORT), 5000)
        socket!!.soTimeout = 5000

        input = DataInputStream(socket!!.getInputStream())
        output = DataOutputStream(socket!!.getOutputStream())

        negotiateSession()
    }

    private fun negotiateSession() {

        val clientNonce = ByteArray(16)
        secureRandom.nextBytes(clientNonce)

        // 1. START
        sendMessage(
            CMD_SESSION_START,
            clientNonce,
            realKey
        )

        // 2. RESPONSE
        val response = readMessage()

        val responsePlain = decryptFrame(response, realKey)

        if (responsePlain.size < 48) {
            throw Exception("Risposta handshake non valida")
        }

        val deviceNonce = responsePlain.copyOfRange(0, 16)
        val receivedHmac = responsePlain.copyOfRange(16, 48)

        val expectedHmac = hmacSha256(realKey, clientNonce)

        if (!receivedHmac.contentEquals(expectedHmac)) {
            throw Exception("Local Key errata o handshake non valido")
        }

        // 3. FINISH
        val finishHmac = hmacSha256(realKey, deviceNonce)

        sendMessage(
            CMD_SESSION_FINISH,
            finishHmac,
            realKey
        )

        // Derivazione session key Tuya 3.5:
        // XOR nonce + AES-GCM con IV = primi 12 byte del client nonce.
        val xorNonce = ByteArray(16)

        for (i in 0 until 16) {
            xorNonce[i] = (clientNonce[i].toInt() xor deviceNonce[i].toInt()).toByte()
        }

        val derived = aesGcmEncrypt(
            realKey,
            clientNonce.copyOfRange(0, 12),
            xorNonce,
            null
        )

        // aesGcmEncrypt restituisce ciphertext + tag.
        // La session key è costituita dai primi 16 byte del ciphertext.
        sessionKey = derived.copyOfRange(0, 16)
    }

    private fun sendMessage(
        command: Int,
        plaintext: ByteArray,
        key: ByteArray
    ) {

        val seq = sequence++
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)

        val encrypted = aesGcmEncrypt(
            key,
            iv,
            plaintext,
            buildAad(seq, command, 12 + plaintext.size + 16)
        )

        val length = encrypted.size + iv.size

        val frame = ByteArray(
            PREFIX.size +
                    2 +
                    4 +
                    4 +
                    4 +
                    iv.size +
                    encrypted.size +
                    SUFFIX.size
        )

        var p = 0

        PREFIX.copyInto(frame, p)
        p += PREFIX.size

        // reserved
        frame[p++] = 0
        frame[p++] = 0

        writeInt(frame, p, seq)
        p += 4

        writeInt(frame, p, command)
        p += 4

        writeInt(frame, p, length)
        p += 4

        iv.copyInto(frame, p)
        p += iv.size

        encrypted.copyInto(frame, p)
        p += encrypted.size

        SUFFIX.copyInto(frame, p)

        output!!.write(frame)
        output!!.flush()
    }

    private fun readMessage(): ByteArray {

        val inStream = input ?: throw Exception("Connessione assente")

        val prefix = ByteArray(4)
        inStream.readFully(prefix)

        if (!prefix.contentEquals(PREFIX)) {
            throw Exception("Frame Tuya non valido")
        }

        val headerRest = ByteArray(14)
        inStream.readFully(headerRest)

        val length =
            ((headerRest[10].toInt() and 0xFF) shl 24) or
            ((headerRest[11].toInt() and 0xFF) shl 16) or
            ((headerRest[12].toInt() and 0xFF) shl 8) or
            (headerRest[13].toInt() and 0xFF)

        if (length < 28 || length > 1024 * 1024) {
            throw Exception("Lunghezza frame non valida: $length")
        }

        val body = ByteArray(length)
        inStream.readFully(body)

        val suffix = ByteArray(4)
        inStream.readFully(suffix)

        if (!suffix.contentEquals(SUFFIX)) {
            throw Exception("Footer Tuya non valido")
        }

        return prefix + headerRest + body + suffix
    }

    private fun decryptFrame(
        frame: ByteArray,
        key: ByteArray
    ): ByteArray {

        if (frame.size < 50) {
            throw Exception("Frame troppo corto")
        }

        val seq = readInt(frame, 6)
        val command = readInt(frame, 10)
        val length = readInt(frame, 14)

        val ivStart = 18
        val iv = frame.copyOfRange(ivStart, ivStart + 12)

        val encryptedStart = ivStart + 12
        val encryptedEnd = encryptedStart + length - 12

        val encrypted = frame.copyOfRange(
            encryptedStart,
            encryptedEnd
        )

        val aad = buildAad(
            seq,
            command,
            length
        )

        return aesGcmDecrypt(
            key,
            iv,
            encrypted,
            aad
        )
    }

    private fun extractPower(plain: ByteArray): Double {

        // Risposta tipica:
        // 4 byte retcode
        // 15 byte header 3.5
        // JSON

        if (plain.size < 19) {
            throw Exception("Risposta DP troppo corta")
        }

        var jsonStart = 4

        if (plain.size >= 19 &&
            plain.copyOfRange(4, 7)
                .contentEquals(byteArrayOf(0x33, 0x2E, 0x35))
        ) {
            jsonStart += 15
        }

        val jsonBytes = plain.copyOfRange(
            jsonStart,
            plain.size
        )

        val json = JSONObject(
            String(jsonBytes, Charsets.UTF_8).trim()
        )

        val dps = json.optJSONObject("dps")
            ?: json.optJSONObject("data")?.optJSONObject("dps")
            ?: throw Exception("DPS non presenti nella risposta")

        val raw = dps.opt("19")
            ?: throw Exception("DP 19 non presente")

        val value = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDouble()
            else -> throw Exception("Valore DP19 non numerico")
        }

        return value / 10.0
    }

    private fun buildAad(
        sequence: Int,
        command: Int,
        length: Int
    ): ByteArray {

        val aad = ByteArray(14)

        aad[0] = 0
        aad[1] = 0

        writeInt(aad, 2, sequence)
        writeInt(aad, 6, command)
        writeInt(aad, 10, length)

        return aad
    }

    private fun aesGcmEncrypt(
        key: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray?
    ): ByteArray {

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        val spec = GCMParameterSpec(128, iv)

        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            spec
        )

        if (aad != null) {
            cipher.updateAAD(aad)
        }

        return cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(
        key: ByteArray,
        iv: ByteArray,
        encrypted: ByteArray,
        aad: ByteArray
    ): ByteArray {

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        val spec = GCMParameterSpec(128, iv)

        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            spec
        )

        cipher.updateAAD(aad)

        return cipher.doFinal(encrypted)
    }

    private fun hmacSha256(
        key: ByteArray,
        data: ByteArray
    ): ByteArray {

        val mac = Mac.getInstance("HmacSHA256")

        mac.init(
            SecretKeySpec(key, "HmacSHA256")
        )

        return mac.doFinal(data)
    }

    private fun writeInt(
        buffer: ByteArray,
        offset: Int,
        value: Int
    ) {

        buffer[offset] = (value ushr 24).toByte()
        buffer[offset + 1] = (value ushr 16).toByte()
        buffer[offset + 2] = (value ushr 8).toByte()
        buffer[offset + 3] = value.toByte()
    }

    private fun readInt(
        buffer: ByteArray,
        offset: Int
    ): Int {

        return ((buffer[offset].toInt() and 0xFF) shl 24) or
                ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
                ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
                (buffer[offset + 3].toInt() and 0xFF)
    }

    fun close() {

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        socket = null
        input = null
        output = null
        sessionKey = null
    }
}
