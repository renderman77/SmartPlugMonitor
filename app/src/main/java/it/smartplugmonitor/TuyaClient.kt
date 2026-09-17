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

        val payload =
            JSONObject()
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
            sessionKey
                ?: throw Exception(
                    "Sessione Tuya non disponibile"
                )
        )

        val frame = readMessage()

        val plain =
            decryptFrame(
                frame,
                sessionKey
                    ?: throw Exception(
                        "Sessione Tuya non disponibile"
                    )
            )

        return extractPower(plain)
    }

    /**
     * Chiede alla presa di aggiornare i Data Point energetici
     * (comando Tuya "UPDATEDPS", 0x12) prima di una lettura, nel
     * tentativo di forzare un valore di potenza più fresco invece di
     * uno eventualmente cacheato dal firmware.
     *
     * NOTA: è un tentativo, non una garanzia — non è confermato che
     * questo modello di presa consideri questo comando per ricalcolare
     * la potenza più spesso. Non lancia eccezioni verso il chiamante:
     * in caso di problemi, la successiva chiamata a getPower() normale
     * prosegue comunque.
     */
    @Synchronized
    fun requestDpsRefresh() {

        try {

            ensureConnected()

            val dpIds = org.json.JSONArray()
            dpIds.put(18)
            dpIds.put(19)
            dpIds.put(20)

            val payload =
                JSONObject()
                    .put("dpId", dpIds)
                    .toString()
                    .toByteArray(Charsets.UTF_8)

            sendMessage(
                CMD_UPDATEDPS,
                payload,
                sessionKey
                    ?: throw Exception(
                        "Sessione Tuya non disponibile"
                    )
            )

            // Leggiamo e scartiamo la risposta di conferma: non
            // assumiamo che contenga già i valori aggiornati, la
            // lettura vera arriva subito dopo con getPower().
            readMessage()

        } catch (_: Exception) {
            // Best-effort: se questo comando fallisce, il polling
            // normale con getPower() prosegue comunque.
        }
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
            throw Exception(
                "La Local Key deve essere di 16 caratteri"
            )
        }

        socket = Socket()

        socket!!.connect(
            InetSocketAddress(
                ipAddress,
                PORT
            ),
            5000
        )

        socket!!.soTimeout = 5000

        input =
            DataInputStream(
                socket!!.getInputStream()
            )

        output =
            DataOutputStream(
                socket!!.getOutputStream()
            )

        negotiateSession()
    }

    private fun negotiateSession() {

        /*
         * TUYA 3.5
         *
         * 1) START
         *    client nonce = 16 byte
         *
         * 2) RESPONSE
         *    device nonce = 16 byte
         *    HMAC(client nonce) = 32 byte
         *
         * 3) FINISH
         *    HMAC(device nonce) = 32 byte
         *
         * Session key:
         * XOR(clientNonce, deviceNonce)
         * AES-GCM con IV = clientNonce[0..11]
         * sessionKey = risultato[12..27]
         */

        val clientNonce = ByteArray(16)
        random.nextBytes(clientNonce)

        // ------------------------------------------------
        // STEP 1 - START
        // ------------------------------------------------

        sendMessage(
            CMD_SESSION_START,
            clientNonce,
            realKey
        )

        // ------------------------------------------------
        // STEP 2 - RESPONSE
        // ------------------------------------------------

        val responseFrame =
            readMessage()

        val responsePlain =
            decryptFrame(
                responseFrame,
                realKey
            )

        /*
         * Alcune implementazioni/firmware possono
         * includere il retcode nei primi 4 byte.
         *
         * Gestiamo entrambe le forme:
         *
         * 48 byte:
         *   nonce 16 + HMAC 32
         *
         * 52 byte:
         *   retcode 4 + nonce 16 + HMAC 32
         */

        val handshakePayload: ByteArray

        when (responsePlain.size) {

            48 -> {
                handshakePayload = responsePlain
            }

            52 -> {

                val retCode =
                    readInt(
                        responsePlain,
                        0
                    )

                if (retCode != 0) {
                    throw Exception(
                        "Handshake rifiutato dalla presa: $retCode"
                    )
                }

                handshakePayload =
                    responsePlain.copyOfRange(
                        4,
                        responsePlain.size
                    )
            }

            else -> {

                /*
                 * Gestione più permissiva:
                 * se il payload è più lungo di 48 byte
                 * e inizia con retcode 0, lo eliminiamo.
                 */

                if (responsePlain.size > 48 &&
                    readInt(responsePlain, 0) == 0
                ) {

                    handshakePayload =
                        responsePlain.copyOfRange(
                            4,
                            responsePlain.size
                        )

                } else {

                    throw Exception(
                        "Risposta handshake non valida: " +
                            "${responsePlain.size} byte"
                    )
                }
            }
        }

        if (handshakePayload.size < 48) {
            throw Exception(
                "Payload handshake troppo corto"
            )
        }

        val deviceNonce =
            handshakePayload.copyOfRange(
                0,
                16
            )

        val receivedHmac =
            handshakePayload.copyOfRange(
                16,
                48
            )

        val expectedHmac =
            hmacSha256(
                realKey,
                clientNonce
            )

        if (!receivedHmac.contentEquals(expectedHmac)) {
            throw Exception(
                "Local Key errata o handshake non valido"
            )
        }

        // ------------------------------------------------
        // STEP 3 - FINISH
        // ------------------------------------------------

        val finishHmac =
            hmacSha256(
                realKey,
                deviceNonce
            )

        sendMessage(
            CMD_SESSION_FINISH,
            finishHmac,
            realKey
        )

        // ------------------------------------------------
        // DERIVAZIONE SESSION KEY
        // ------------------------------------------------

        val xorNonce = ByteArray(16)

        for (i in 0 until 16) {

            xorNonce[i] =
                (
                    clientNonce[i].toInt()
                        xor deviceNonce[i].toInt()
                    ).toByte()
        }

        val derivationIv =
            clientNonce.copyOfRange(
                0,
                12
            )

        val encrypted =
            aesGcmEncrypt(
                key = realKey,
                iv = derivationIv,
                plaintext = xorNonce,
                aad = null
            )

        /*
         * Java AES/GCM restituisce:
         *
         * ciphertext (16 byte)
         * +
         * authentication tag (16 byte)
         *
         * TinyTuya considera anche l'IV davanti:
         *
         * IV       12 byte
         * DATA     16 byte
         * TAG      16 byte
         *
         * Quindi:
         *
         * complete = IV + encrypted
         *
         * session key = complete[12..27]
         */

        val complete =
            derivationIv + encrypted

        if (complete.size < 28) {
            throw Exception(
                "Derivazione session key non valida"
            )
        }

        sessionKey =
            complete.copyOfRange(
                12,
                28
            )
    }

    private fun sendMessage(
        command: Int,
        plaintext: ByteArray,
        encryptionKey: ByteArray
    ) {

        val seq = sequence++

        val iv = ByteArray(12)
        random.nextBytes(iv)

        /*
         * Per Tuya 3.5:
         *
         * header =
         * reserved 2
         * sequence 4
         * command 4
         * length 4
         *
         * length conta:
         * IV + ciphertext + GCM tag
         */

        val encrypted =
            aesGcmEncrypt(
                key = encryptionKey,
                iv = iv,
                plaintext = plaintext,
                aad = buildHeader(
                    seq,
                    command,
                    12 + plaintext.size + 16
                )
            )

        val length =
            iv.size + encrypted.size

        val header =
            buildHeader(
                seq,
                command,
                length
            )

        val frame =
            ByteArray(
                PREFIX.size +
                    header.size +
                    iv.size +
                    encrypted.size +
                    SUFFIX.size
            )

        var position = 0

        PREFIX.copyInto(
            frame,
            position
        )

        position += PREFIX.size

        header.copyInto(
            frame,
            position
        )

        position += header.size

        iv.copyInto(
            frame,
            position
        )

        position += iv.size

        encrypted.copyInto(
            frame,
            position
        )

        position += encrypted.size

        SUFFIX.copyInto(
            frame,
            position
        )

        output!!.write(frame)
        output!!.flush()
    }

    private fun readMessage(): ByteArray {

        val stream =
            input
                ?: throw Exception(
                    "Connessione assente"
                )

        val prefix = ByteArray(4)

        stream.readFully(prefix)

        if (!prefix.contentEquals(PREFIX)) {
            throw Exception(
                "Frame Tuya non valido"
            )
        }

        /*
         * Header:
         *
         * reserved 2
         * sequence 4
         * command 4
         * length 4
         *
         * = 14 byte
         */

        val header = ByteArray(14)

        stream.readFully(header)

        val length =
            readInt(
                header,
                10
            )

        if (length < 28 ||
            length > 1024 * 1024
        ) {
            throw Exception(
                "Lunghezza frame non valida: $length"
            )
        }

        val body =
            ByteArray(length)

        stream.readFully(body)

        val suffix =
            ByteArray(4)

        stream.readFully(suffix)

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

        /*
         * 0..3   prefix
         * 4..17  header
         * 18..29 IV
         * 30..   ciphertext + tag
         */

        val header =
            frame.copyOfRange(
                4,
                18
            )

        val length =
            readInt(
                header,
                10
            )

        if (length < 28) {
            throw Exception(
                "Payload Tuya non valido"
            )
        }

        val iv =
            frame.copyOfRange(
                18,
                30
            )

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

        if (plain.size < 4) {
            throw Exception(
                "Risposta DP troppo corta"
            )
        }

        var position = 0

        /*
         * Tuya 3.5:
         *
         * retcode = primi 4 byte
         */

        val retCode =
            readInt(
                plain,
                0
            )

        position += 4

        if (retCode != 0) {
            throw Exception(
                "La presa ha restituito errore: $retCode"
            )
        }

        /*
         * Dopo il retcode può esserci
         * il blocco header da 15 byte.
         *
         * Lo riconosciamo dal prefisso "3.5".
         */

        if (plain.size >= position + 15) {

            val versionBytes =
                plain.copyOfRange(
                    position,
                    position + 3
                )

            if (versionBytes.contentEquals(
                    byteArrayOf(
                        0x33,
                        0x2E,
                        0x35
                    )
                )
            ) {
                position += 15
            }
        }

        val jsonBytes =
            plain.copyOfRange(
                position,
                plain.size
            )

        val jsonText =
            String(
                jsonBytes,
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

        /*
         * Nel tuo modello:
         *
         * DP19 / 10 = Watt
         */

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
            SecretKeySpec(
                key,
                "AES"
            ),
            GCMParameterSpec(
                128,
                iv
            )
        )

        if (aad != null) {
            cipher.updateAAD(aad)
        }

        return cipher.doFinal(
            plaintext
        )
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
            SecretKeySpec(
                key,
                "AES"
            ),
            GCMParameterSpec(
                128,
                iv
            )
        )

        cipher.updateAAD(aad)

        return cipher.doFinal(
            encrypted
        )
    }

    private fun hmacSha256(
        key: ByteArray,
        data: ByteArray
    ): ByteArray {

        val mac =
            Mac.getInstance(
                "HmacSHA256"
            )

        mac.init(
            SecretKeySpec(
                key,
                "HmacSHA256"
            )
        )

        return mac.doFinal(
            data
        )
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
