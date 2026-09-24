package it.smartplugmonitor
import org.json.JSONObject
import java.io.*
import java.net.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.*

class TuyaClient(private val ipAddress: String, private val localKey: String) {
    companion object {
        private const val PORT = 6668
        private val PREFIX = byteArrayOf(0, 0, 0x66, 0x99.toByte())
        private val SUFFIX = byteArrayOf(0, 0, 0x99.toByte(), 0x66)
    }
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private val random = SecureRandom()
    private val realKey: ByteArray get() = localKey.toByteArray(Charsets.UTF_8)
    private var sessionKey: ByteArray? = null
    private var sequence = 1

    /**
     * Lettura ATTIVA — usata nella fase "normale" (interrogazione a
     * intervalli, es. ogni 20s). Comando UPDATEDPS (0x12): con il
     * traffico verso il cloud Tuya bloccato dal firewall di rete
     * locale, questo comando si è dimostrato affidabile e preciso nei
     * test reali (zero errori, rilevamento entro un solo ciclo).
     */
    @Synchronized fun getPower(): Double {
        ensureConnected()
        val targetKey = sessionKey ?: throw Exception("Session not available")
        val dpIds = org.json.JSONArray().put(18).put(19).put(20)
        val payload = JSONObject().put("dpId", dpIds).toString().toByteArray(Charsets.UTF_8)
        sendMessage(0x12, payload, targetKey)
        return parsePowerFromJson(cleanTuyaPayload(decryptFrame(readMessage(), targetKey)))
    }

    /**
     * Battito di mantenimento (nessuna richiesta di dati) — usato
     * nella fase "allerta" (ascolto passivo) per tenere viva la
     * connessione senza interrogare la presa.
     */
    @Synchronized fun sendHeartbeat() {
        ensureConnected()
        val targetKey = sessionKey ?: throw Exception("Session not available")
        sendMessage(0x09, "{}".toByteArray(Charsets.UTF_8), targetKey)
        try { readMessage() } catch (_: Exception) {}
    }

    /**
     * NON manda nessuna richiesta — usata nella fase "allerta". Resta
     * in ascolto sul socket per al massimo [timeoutMs] millisecondi, e
     * ritorna il valore di potenza SOLO se la presa ha spontaneamente
     * mandato un aggiornamento in quella finestra. Ritorna null se non
     * arriva nulla (timeout) o se arriva un messaggio senza il dato di
     * potenza (es. un ACK) — in entrambi i casi NON è un errore.
     */
    @Synchronized fun listenForUpdate(timeoutMs: Int): Double? {
        ensureConnected()
        val targetKey = sessionKey ?: throw Exception("Session not available")
        socket?.soTimeout = timeoutMs
        return try {
            parsePowerFromJson(cleanTuyaPayload(decryptFrame(readMessage(), targetKey)))
        } catch (_: SocketTimeoutException) {
            null
        } catch (e: Exception) {
            if (e.message == "No DPS" || e.message == "No Power DP") null else throw e
        }
    }

    private fun ensureConnected() {
        if (socket?.isConnected == true && socket?.isClosed == false && sessionKey != null) return
        close()
        if (realKey.size != 16) throw Exception("Local Key must be 16 characters")
        socket = Socket().apply {
            // Margine più ampio rispetto ai 2s originali: l'handshake
            // iniziale di una connessione nuova ha più passaggi della
            // semplice lettura di routine, ed è il momento in cui gli
            // errori di connessione sono comparsi più spesso.
            connect(InetSocketAddress(ipAddress, PORT), 5000)
            soTimeout = 5000
        }
        input = DataInputStream(socket!!.getInputStream())
        output = DataOutputStream(socket!!.getOutputStream())
        negotiateSession()
    }

    private fun negotiateSession() {
        val clientNonce = ByteArray(16).also { random.nextBytes(it) }
        sendMessage(0x03, clientNonce, realKey)
        val responsePlain = decryptFrame(readMessage(), realKey)
        val handshakePayload = if (responsePlain.size == 52 || (responsePlain.size > 48 && readInt(responsePlain, 0) == 0)) {
            responsePlain.copyOfRange(4, responsePlain.size)
        } else responsePlain
        if (handshakePayload.size < 48) throw Exception("Handshake payload too short")
        val deviceNonce = handshakePayload.copyOfRange(0, 16)
        if (!handshakePayload.copyOfRange(16, 48).contentEquals(hmacSha256(realKey, clientNonce))) throw Exception("Wrong Local Key")
        sendMessage(0x05, hmacSha256(realKey, deviceNonce), realKey)
        val xorNonce = ByteArray(16) { i -> (clientNonce[i].toInt() xor deviceNonce[i].toInt()).toByte() }
        sessionKey = (clientNonce.copyOfRange(0, 12) + aesGcmEncrypt(realKey, clientNonce.copyOfRange(0, 12), xorNonce, null)).copyOfRange(12, 28)
    }

    private fun sendMessage(command: Int, plaintext: ByteArray, encryptionKey: ByteArray) {
        val seq = sequence++
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val encrypted = aesGcmEncrypt(encryptionKey, iv, plaintext, buildHeader(seq, command, 12 + plaintext.size + 16))
        val header = buildHeader(seq, command, iv.size + encrypted.size)
        val frame = PREFIX + header + iv + encrypted + SUFFIX
        output!!.write(frame)
        output!!.flush()
    }

    private fun readMessage(): ByteArray {
        val stream = input ?: throw Exception("No connection")
        val prefix = ByteArray(4).also { stream.readFully(it) }
        if (!prefix.contentEquals(PREFIX)) throw Exception("Invalid frame prefix")
        val header = ByteArray(14).also { stream.readFully(it) }
        val length = readInt(header, 10)
        val body = ByteArray(length).also { stream.readFully(it) }
        val suffix = ByteArray(4).also { stream.readFully(it) }
        return prefix + header + body + suffix
    }

    private fun decryptFrame(frame: ByteArray, encryptionKey: ByteArray): ByteArray {
        val length = readInt(frame.copyOfRange(4, 18), 10)
        return aesGcmDecrypt(encryptionKey, frame.copyOfRange(18, 30), frame.copyOfRange(30, 30 + length - 12), frame.copyOfRange(4, 18))
    }

    private fun cleanTuyaPayload(plain: ByteArray): ByteArray {
        var pos = 4
        if (plain.size >= pos + 15 && plain.copyOfRange(pos, pos + 3).contentEquals(byteArrayOf(0x33, 0x2E, 0x35))) pos += 15
        return plain.copyOfRange(pos, plain.size)
    }

    private fun parsePowerFromJson(jsonBytes: ByteArray): Double {
        val json = JSONObject(String(jsonBytes, Charsets.UTF_8).trim())
        val dps = json.optJSONObject("dps") ?: json.optJSONObject("data")?.optJSONObject("dps") ?: throw Exception("No DPS")
        val raw = dps.opt("19") ?: dps.opt("103") ?: throw Exception("No Power DP")
        return (if (raw is Number) raw.toDouble() else raw.toString().toDouble()) / 10.0
    }

    private fun buildHeader(seq: Int, cmd: Int, len: Int): ByteArray {
        val header = ByteArray(14)
        header[0] = 0
        header[1] = 0
        writeInt(header, 2, seq)
        writeInt(header, 6, cmd)
        writeInt(header, 10, len)
        return header
    }

    private fun aesGcmEncrypt(k: ByteArray, iv: ByteArray, p: ByteArray, a: ByteArray?) = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv)); a?.let { updateAAD(it) }; doFinal(p)
    }
    private fun aesGcmDecrypt(k: ByteArray, iv: ByteArray, e: ByteArray, a: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv)); updateAAD(a); doFinal(e)
    }
    private fun hmacSha256(k: ByteArray, d: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(k, "HmacSHA256")); doFinal(d) }
    private fun writeInt(b: ByteArray, o: Int, v: Int) { b[o]=(v ushr 24).toByte(); b[o+1]=(v ushr 16).toByte(); b[o+2]=(v ushr 8).toByte(); b[o+3]=v.toByte() }
    private fun readInt(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o+1].toInt() and 0xFF) shl 16) or ((b[o+2].toInt() and 0xFF) shl 8) or (b[o+3].toInt() and 0xFF)
    fun close() { try { socket?.close() } catch (_: Exception) {}; socket = null; input = null; output = null; sessionKey = null }
}
