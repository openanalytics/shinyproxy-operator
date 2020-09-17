package eu.openanalytics.shinyproxyoperator

import java.math.BigInteger
import java.security.MessageDigest

fun String.sha1(): String {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.reset()
    digest.update(this.toByteArray(Charsets.UTF_8))
    return String.format("%040x", BigInteger(1, digest.digest()))
}