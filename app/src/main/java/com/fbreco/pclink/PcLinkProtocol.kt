package com.fbreco.pclink

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The pairing key never travels over the LAN. Telemetry is authenticated, not encrypted. */
object PcLinkProtocol {
    fun sign(key: ByteArray, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun verifyConnection(key: ByteArray, challenge: String?, proof: String?): Boolean {
        if (challenge == null || proof == null || !challenge.matches(Regex("[0-9a-f]{64}")) ||
            !proof.matches(Regex("[0-9a-f]{64}"))) return false
        return MessageDigest.isEqual(
            sign(key, "connect\n$challenge").toByteArray(Charsets.US_ASCII),
            proof.toByteArray(Charsets.US_ASCII),
        )
    }

    fun signTelemetry(key: ByteArray, challenge: String, payload: String) =
        sign(key, "telemetry\n$challenge\n$payload")
}
