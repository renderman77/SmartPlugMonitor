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
        private const val CMD_UPDATEDPS = 0x12

        private val PREFIX =
            byteArrayOf(
                0x00,
                0x00,
                0x66,
                0x99.toByte()
            )

        private val SUFFIX =
            byteArrayOf(
                0x00,
                0x00,
                0x99.toByte(),
                0x66
            )
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    private val random = SecureRandom()

    private val realKey: ByteArray
        get() = localKey.toByteArray(Charsets.UTF_8)

    private var sessionKey: ByteArray? = null
    private var sequence = 1

    @Synchronized
    fun getPower(): Double {
        ensureConnected()

        val dpIds = org.json.JSONArray()
        dpIds.put(18)
        dpIds.put(19)
        dpIds.put(20)

        val payload = JSONObject()
            .put("dpId", dpIds)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val targetKey = sessionKey ?: throw Exception("Sessione Tuya non disponibile")

        // 1. Inviamo il comando 0x12 per forzare il refresh hardware interno alla presa
        try {
            sendMessage(CMD_UPDATEDPS, payload, targetKey)
            val frame = readMessage()
            val plain = decryptFrame(frame, targetKey)
            val jsonBytes = cleanTuyaPayload(plain)
            return parsePowerFromJson(jsonBytes)
        } catch (_: Exception) {
            // Se il comando 0x12 restituisce dati vuoti o fallisce il parsing JSON,
            // non andiamo in crash ma passiamo subito al recupero passivo standard qui sotto
        }

        // 2. Recupero passivo: se la presa non ha sputato il JSON direttamente sul comando 0x12,
        // leggiamo lo stato aggiornato tramite CMD_DP_QUERY_NEW
        return getPowerPassive()
    }

    @Synchronized
    fun getPowerPassive(): Double {
        ensureConnected()

        val payload = JSONObject()
            .put("data", JSONObject().put("dps", JSONObject()))
            .toString()
            .toByteArray(Charsets.UTF_8)

        val targetKey = sessionKey ?: throw Exception("Sessione Tuya non disponibile")
        sendMessage(CMD_DP_QUERY_NEW, payload, targetKey)

        val frame = readMessage()
        val plain = decryptFrame(frame, targetKey)

        val jsonBytes = cleanTuyaPayload(plain)
        return parsePowerFromJson(jsonBytes)
    }

    private fun ensureConnected() {
        if (socket?.isConnected == true &&
            socket?.isClosed == false &&
            sessionKey != null
        ) {
            return
        }

        close()

        if (realKey.size != 16) {
            throw Exception("La Local Key deve essere di 16 caratteri")
        }

        socket = Socket()
        socket!!.connect(InetSocketAddress(ipAddress, PORT), 4000)
        socket!!.soTimeout = 4000

        input = DataInputStream(socket!!.getInputStream())
        output = DataOutputStream(socket!!.getOutputStream())

        negotiateSession()
    }

    private fun negotiateSession() {
        val clientNonce = ByteArray(16)
        random.nextBytes(clientNonce)

        sendMessage(CMD_SESSION_START, clientNonce, realKey)

        val responseFrame = readMessage()
        val responsePlain = decryptFrame(responseFrame, realKey)

        val handshakePayload: ByteArray
        when (responsePlain.size) {
            48 -> {
                handshakePayload = responsePlain
            }
            52 -> {
                val retCode = readInt(responsePlain, 0)
                if (retCode != 0) {
                    throw Exception("Handshake inviato rifiutato dalla presa: $retCode")
                }
                handshakePayload = responsePlain.copyOfRange(4, responsePlain.size)
            }
            else -> {
                if (responsePlain.size > 48 && readInt(responsePlain, 0) == 0) {
                    handshakePayload = responsePlain.copyOfRange(4, responsePlain.size)
                } else {
                    throw Exception("Risposta handshake non valida: ${responsePlain.size} byte")
                }
            }
        }

        if (handshakePayload.size < 48) {
            throw Exception("Payload handshake troppo corto")
        }

        val deviceNonce = handshakePayload.copyOfRange(0, 16)
        val receivedHmac = handshakePayload.copyOfRange(16, 48)
        val expectedHmac = hmacSha256(realKey, clientNonce)

        if (!receivedHmac.contentEquals(expectedHmac)) {
            throw Exception("Local Key errata o handshake non valido")
        }

        val finishHmac = hmacSha256(realKey, deviceNonce)
        sendMessage(CMD_SESSION_FINISH, finishHmac, realKey)

        val xorNonce = ByteArray(16)
        for (i in 0 until 16) {
            xorNonce[i] = (clientNonce[i].toInt() xor deviceNonce[i].toInt()).toByte()
        }

        val derivationIv = clientNonce.copyOfRange(0, 12)
        val encrypted = aesGcmEncrypt(
            key = realKey,
            iv = derivationIv,
            plaintext = xorNonce,
            aad = null
        )

        val complete = derivationIv + encrypted
        if (complete.size < 28) {
            throw Exception("Derivazione session key non valida")
        }

        sessionKey = complete.copyOfRange(12, 28)
    }

    private fun sendMessage(
        command: Int,
        plaintext: ByteArray,
        encryptionKey: ByteArray
    ) {
        val seq = sequence++
        val iv = ByteArray(12)
        random.nextBytes(iv)

        val encrypted = aesGcmEncrypt(
            key = encryptionKey,
            iv = iv,
            plaintext = plaintext,
            aad = buildHeader(seq, command, 12 + plaintext.size + 16)
        )

        val length = iv.size + encrypted.size
        val header = buildHeader(seq, command, length)

        val frame = ByteArray(PREFIX.size + header.size + iv.size + encrypted.size + SUFFIX.size)
        var position = 0

        PREFIX.copyInto(frame, position)
        position += PREFIX.size

        header.copyInto(frame, position)
        position += header.size

        iv.copyInto(frame, position)
        position += iv.size

        encrypted.copyInto(frame, position)
        position += encrypted.size

        SUFFIX.copyInto(frame, position)

        output!!.write(frame)
        output!!.flush()
    }

    private fun readMessage(): ByteArray {
        val stream = input ?: throw Exception("Connessione assente")
        val prefix = ByteArray(4)
        stream.readFully(prefix)

        if (!prefix.contentEquals(PREFIX)) {
            throw Exception("Frame Tuya non valido")
        }

        val header = ByteArray(14)
        stream.readFully(header)

        val length = readInt(header, 10)
        if (length < 28 || length > 1024 * 1024) {
            throw Exception("Lunghezza frame non valida: $length")
        }

        val body = ByteArray(length)
        stream.readFully(body)

        val suffix = ByteArray(4)
        stream.readFully(suffix)

        if (!suffix.contentEquals(SUFFIX)) {
            throw Exception("Footer Tuya non valido")
        }

        return prefix + header + body + suffix
    }

    private fun decryptFrame(
        frame: ByteArray,
        encryptionKey: ByteArray
    ): ByteArray {
        if (frame.size < 50) {
            throw Exception("Frame Tuya troppo corto")
        }

        val header = frame.copyOfRange(4, 18)
        val length = readInt(header, 10)

        if (length < 28) {
            throw Exception("Payload Tuya non valido")
        }

        val iv = frame.copyOfRange(18, 30)
        val encrypted = frame.copyOfRange(30, 30 + length - 12)

        return aesGcmDecrypt(
            key = encryptionKey,
            iv = iv,
            encrypted = encrypted,
            aad = header
        )
    }

    private fun cleanTuyaPayload(plain: ByteArray): ByteArray {
        if (plain.size < 4) {
            throw Exception("Risposta DP troppo corta")
        }

        var position = 0
        val retCode = readInt(plain, 0)
        position += 4

        if (retCode != 0) {
            throw Exception("La presa ha restituito errore: $retCode")
        }

        if (plain.size >= position + 15) {
            val versionBytes = plain.copyOfRange(position, position + 3)
            if (versionBytes.contentEquals(byteArrayOf(0x33, 0x2E, 0x35))) {
                position += 15
            }
        }

        return plain.copyOfRange(position, plain.size)
    }

    private fun parsePowerFromJson(jsonBytes: ByteArray): Double {
        val jsonText = String(jsonBytes, Charsets.UTF_8).trim()
        if (jsonText.isEmpty()) {
            throw Exception("Risposta DP senza JSON")
        }

        val json = JSONObject(jsonText)
        val dps = json.optJSONObject("dps")
            ?: json.optJSONObject("data")?.optJSONObject("dps")
            ?: throw Exception("DPS non presenti nella risposta")

        val raw = dps.opt("19") ?: dps.opt("103") ?: throw Exception("DP Potenza non trovato")

        val value = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDouble()
            else -> throw Exception("Valore Potenza non numerico")
        }

        return value / 10.0
    }

    private fun buildHeader(
        sequence: Int,
        command: Int,
        length: Int
    ): ByteArray {
        val header = ByteArray(14)
        header[0] = 0
        header[1] = 0

        writeInt(header, 2, sequence)
        writeInt(header, 6, command)
        writeInt(header, 10, length)

        return header
    }

    private fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, encrypted: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value ushr 24).toByte()
        buffer[offset + 1] = (value ushr 16).toByte()
        buffer[offset + 2] = (value ushr 8).toByte()
        buffer[offset + 3] = value.toByte()
    }

    private fun readInt(buffer: ByteArray, offset: Int): Int {
        return (((buffer[offset].toInt() and 0xFF) shl 24) or
                ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
                ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
                (buffer[offset + 3].toInt() and 0xFF))
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
