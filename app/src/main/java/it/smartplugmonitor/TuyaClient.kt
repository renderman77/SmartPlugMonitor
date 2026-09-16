package it.smartplugmonitor

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
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

    private val PREFIX =
    byteArrayOf(0x00, 0x00, 0x66, 0x99.toByte())

    private val SUFFIX =
    byteArrayOf(0x00, 0x00, 0x99.toByte(), 0x66)

    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    private val random = SecureRandom()

    private val key: ByteArray
        get() = localKey.toByteArray(Charsets.UTF_8)

    private var sessionKey: ByteArray? = null
    private var sequence = 1

    @Synchronized
    fun getPower(): Double {

        ensureConnected()

        val payload = JSONObject()
            .put(
                "data",
                JSONObject().put(
                    "dps",
                    JSONObject()
                )
            )
            .toString()
            .toByteArray(Charsets.UTF_8)

        sendMessage(
            CMD_DP_QUERY_NEW,
            payload,
            sessionKey ?: throw Exception("Sessione Tuya non disponibile")
        )

        val frame = readMessage()

        val plain = decryptFrame(
            frame,
            sessionKey ?: throw Exception("Sessione Tuya non disponibile")
        )

        return extractPower(plain)
    }

    private fun ensureConnected() {

        if (socket?.isConnected == true &&
            socket?.isClosed == false
        ) {
            return
        }

        close()

        if (key.size != 16) {
            throw Exception(
                "La Local Key deve essere di 16 caratteri"
            )
        }

        socket = Socket()

        socket!!.connect(
            InetSocketAddress(ipAddress, PORT),
            5000
        )

        socket!!.soTimeout = 5000

        input =
            DataInputStream(socket!!.getInputStream())

        output =
            DataOutputStream(socket!!.getOutputStream())

        negotiateSession()
    }

    private fun negotiateSession() {

        // -------------------------------------------------
        // 1. CLIENT -> DEVICE
        //    START 0x03 + client nonce
        // -------------------------------------------------

        val clientNonce = ByteArray(16)
        random.nextBytes(clientNonce)

        sendMessage(
            CMD_SESSION_START,
            clientNonce,
            key
        )

        // -------------------------------------------------
        // 2. DEVICE -> CLIENT
        //    RESPONSE 0x04
        //    device nonce + HMAC(client nonce)
        // -------------------------------------------------

        val response = readMessage()

        val responsePlain =
            decryptFrame(response, key)

        if (responsePlain.size < 48) {
            throw Exception(
                "Risposta handshake non valida"
            )
        }

        val deviceNonce =
            responsePlain.copyOfRange(0, 16)

        val receivedHmac =
            responsePlain.copyOfRange(16, 48)

        val expectedHmac =
            hmacSha256(key, clientNonce)

        if (!receivedHmac.contentEquals(expectedHmac)) {
            throw Exception(
                "Local Key errata o handshake non valido"
            )
        }

        // -------------------------------------------------
        // 3. CLIENT -> DEVICE
        //    FINISH 0x05 + HMAC(device nonce)
        // -------------------------------------------------

        val finishHmac =
            hmacSha256(key, deviceNonce)

        sendMessage(
            CMD_SESSION_FINISH,
            finishHmac,
            key
        )

        // -------------------------------------------------
        // SESSION KEY
        //
        // Tuya 3.5:
        // XOR dei due nonce
        // AES-GCM con IV = primi 12 byte del client nonce
        // risultato [12:28]
        // -------------------------------------------------

        val xorNonce = ByteArray(16)

        for (i in 0 until 16) {
            xorNonce[i] =
                (deviceNonce[i].toInt()
                    xor clientNonce[i].toInt()).toByte()
        }

        val derived =
            aesGcmEncrypt(
                key = key,
                iv = clientNonce.copyOfRange(0, 12),
                plaintext = xorNonce,
                aad = null
            )

        if (derived.size < 28) {
            throw Exception(
                "Impossibile generare session key"
            )
        }

        sessionKey =
            derived.copyOfRange(0, 16)
    }

    private fun sendMessage(
        command: Int,
        plaintext: ByteArray,
        encryptionKey: ByteArray
    ) {

        val seq = sequence++

        val iv = ByteArray(12)
        random.nextBytes(iv)

        // IV + ciphertext + GCM tag
        val length =
            12 + plaintext.size + 16

        // AAD = tutto l'header dopo il prefix.
        val aad =
            buildHeader(
                seq,
                command,
                length
            )

        val encrypted =
            aesGcmEncrypt(
                key = encryptionKey,
                iv = iv,
                plaintext = plaintext,
                aad = aad
            )

        val frame =
            ByteArray(
                PREFIX.size +
                    aad.size +
                    iv.size +
                    encrypted.size +
                    SUFFIX.size
            )

        var pos = 0

        PREFIX.copyInto(frame, pos)
        pos += PREFIX.size

        aad.copyInto(frame, pos)
        pos += aad.size

        iv.copyInto(frame, pos)
        pos += iv.size

        encrypted.copyInto(frame, pos)
        pos += encrypted.size

        SUFFIX.copyInto(frame, pos)

        output!!.write(frame)
        output!!.flush()
    }

    private fun readMessage(): ByteArray {

        val inputStream =
            input ?: throw Exception("Connessione assente")

        val prefix = ByteArray(4)
        inputStream.readFully(prefix)

        if (!prefix.contentEquals(PREFIX)) {
            throw Exception(
                "La presa ha restituito un frame non valido"
            )
        }

        // reserved 2 + sequence 4 + command 4 + length 4
        val header = ByteArray(14)
        inputStream.readFully(header)

        val length = readInt(header, 10)

        if (length < 28 || length > 1024 * 1024) {
            throw Exception(
                "Lunghezza frame non valida: $length"
            )
        }

        val body = ByteArray(length)
        inputStream.readFully(body)

        val suffix = ByteArray(4)
        inputStream.readFully(suffix)

        if (!suffix.contentEquals(SUFFIX)) {
            throw Exception(
                "Footer Tuya non valido"
            )
        }

        return prefix + header + body + suffix
    }

    private fun decryptFrame(
        frame: ByteArray,
        encryptionKey: ByteArray
    ): ByteArray {

        if (frame.size < 50) {
            throw Exception(
                "Frame Tuya troppo corto"
            )
        }

        // Prefix = 4
        // Header = 14
        // IV = 12
        val header =
            frame.copyOfRange(4, 18)

        val length =
            readInt(header, 10)

        if (length < 28) {
            throw Exception(
                "Lunghezza payload non valida"
            )
        }

        val iv =
            frame.copyOfRange(18, 30)

        val encrypted =
            frame.copyOfRange(
                30,
                30 + length - 12
            )

        return aesGcmDecrypt(
            key = encryptionKey,
            iv = iv,
            encrypted = encrypted,
            aad = header
        )
    }

    private fun extractPower(
        plain: ByteArray
    ): Double {

        // 3.5 DP_QUERY_NEW:
        //
        // primi 4 byte = return code
        // poi JSON
        //
        // Il protocollo documenta DP_QUERY_NEW
        // tra i comandi che NON richiedono
        // il version header 3.5.

        if (plain.size <= 4) {
            throw Exception(
                "Risposta DP vuota"
            )
        }

        val retCode =
            readInt(plain, 0)

        if (retCode != 0) {
            throw Exception(
                "La presa ha restituito errore: $retCode"
            )
        }

        val jsonText =
            String(
                plain.copyOfRange(4, plain.size),
                Charsets.UTF_8
            ).trim()

        if (jsonText.isEmpty()) {
            throw Exception(
                "Risposta DP senza JSON"
            )
        }

        val json =
            JSONObject(jsonText)

        val dps =
            json.optJSONObject("dps")
                ?: json
                    .optJSONObject("data")
                    ?.optJSONObject("dps")
                ?: throw Exception(
                    "DPS non presenti nella risposta"
                )

        val raw =
            dps.opt("19")
                ?: throw Exception(
                    "DP 19 non presente"
                )

        val value =
            when (raw) {
                is Number ->
                    raw.toDouble()

                is String ->
                    raw.toDouble()

                else ->
                    throw Exception(
                        "Valore DP19 non numerico"
                    )
            }

        return value / 10.0
    }

    private fun buildHeader(
        sequence: Int,
        command: Int,
        length: Int
    ): ByteArray {

        val header = ByteArray(14)

        // reserved
        header[0] = 0
        header[1] = 0

        writeInt(
            header,
            2,
            sequence
        )

        writeInt(
            header,
            6,
            command
        )

        writeInt(
            header,
            10,
            length
        )

        return header
    }

    private fun aesGcmEncrypt(
        key: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray?
    ): ByteArray {

        val cipher =
            Cipher.getInstance(
                "AES/GCM/NoPadding"
            )

        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv)
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

        val cipher =
            Cipher.getInstance(
                "AES/GCM/NoPadding"
            )

        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv)
        )

        cipher.updateAAD(aad)

        return cipher.doFinal(encrypted)
    }

    private fun hmacSha256(
        key: ByteArray,
        data: ByteArray
    ): ByteArray {

        val mac =
            Mac.getInstance("HmacSHA256")

        mac.init(
            SecretKeySpec(
                key,
                "HmacSHA256"
            )
        )

        return mac.doFinal(data)
    }

    private fun writeInt(
        buffer: ByteArray,
        offset: Int,
        value: Int
    ) {

        buffer[offset] =
            (value ushr 24).toByte()

        buffer[offset + 1] =
            (value ushr 16).toByte()

        buffer[offset + 2] =
            (value ushr 8).toByte()

        buffer[offset + 3] =
            value.toByte()
    }

    private fun readInt(
        buffer: ByteArray,
        offset: Int
    ): Int {

        return (
            ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)
        )
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
