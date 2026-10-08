package link.hector.freellee.util

import java.security.MessageDigest

/** Generate a unique 16-char hash ID for a step interval. */
fun intervalId(tStart: Long, tEnd: Long, steps: Int): String {
    val input = "$tStart-$tEnd-$steps"
    val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return hash.toHex().take(16)
}

private fun ByteArray.toHex(): String {
    return joinToString("") { "%02x".format(it) }
}
