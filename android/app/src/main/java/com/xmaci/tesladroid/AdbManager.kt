package com.xmaci.tesladroid

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * ADB client identity for talking to this phone's own adbd over Wireless debugging.
 *
 * The RSA key and self-signed certificate are created once and kept in the app's private
 * files; after the one-time pairing, adbd trusts this identity until the user revokes
 * debugging authorisations.
 */
class AdbManager private constructor(ctx: Context) : AbsAdbConnectionManager() {

    companion object {
        @Volatile private var instance: AdbManager? = null

        fun get(ctx: Context): AdbManager =
            instance ?: synchronized(this) { instance ?: AdbManager(ctx.applicationContext).also { instance = it } }
    }

    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val keyFile = File(ctx.filesDir, "adb.key")
        val certFile = File(ctx.filesDir, "adb.crt")
        if (keyFile.exists() && certFile.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            val now = Date()
            val until = Date(now.time + 20L * 365 * 24 * 3600 * 1000)
            val name = X500Name("CN=TeslaDroid")
            val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.time), now, until, name, kp.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
            key = kp.private
            cert = JcaX509CertificateConverter().getCertificate(holder)
            keyFile.writeBytes(key.encoded)
            certFile.writeBytes(cert.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "TeslaDroid"
}
