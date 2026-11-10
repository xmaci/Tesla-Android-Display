package com.xmaci.tesladroid

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.util.Base64

/**
 * Licence: a 30-day trial from the first start, then an activation code made by the owner's
 * generator (tools/license/tdlicense.py). A code is signed with the owner's Ed25519 key (the private
 * key never leaves the owner's computer) and is bound to one phone's device code, so it does not work
 * on another phone. Checked offline.
 *
 * Never cuts a running session: only the next Start is blocked.
 */
object License {
    /** The owner's PUBLIC key (hex, 32 bytes) from `tdlicense.py keygen`. Safe to publish. */
    const val PUBLIC_KEY_HEX = "REPLACE_WITH_PUBLIC_KEY"

    /** SHA-256 of the certificate the official APK is signed with; empty = not checked. */
    const val SIGNER_SHA256 = "b40561a4d2b9a38c3e138829d416daa243c4ca0ac722cba596b3528e3c5e89e8"

    const val TRIAL_DAYS = 30
    private const val PREFS = "licence"
    private const val DAY_MS = 86_400_000L
    private const val FOREVER = 0xFFFF
    private val EPOCH_MS = java.time.LocalDate.of(2026, 1, 1).toEpochDay() * DAY_MS

    sealed class State {
        data class Trial(val daysLeft: Int) : State()
        data class Active(val untilMs: Long?) : State()        // null = no end
        data class Expired(val why: String) : State()
        val canStart get() = this !is Expired
    }

    // ---------- device code ----------

    @SuppressLint("HardwareIds")
    fun deviceBytes(ctx: Context): ByteArray {
        // ANDROID_ID: per app signing key and user; survives reinstall and updates, changes on factory reset.
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "none"
        return MessageDigest.getInstance("SHA-256").digest("tesladroid:$id".toByteArray()).copyOf(6)
    }

    fun deviceCode(ctx: Context): String {
        val s = base32(deviceBytes(ctx))          // 10 characters
        return "TD-${s.substring(0, 5)}-${s.substring(5)}"
    }

    // ---------- codes ----------

    data class Parsed(val device: ByteArray, val day: Int)

    /** Verifies [code] against [publicKeyHex]; returns the payload or null (bad format or signature). */
    fun parse(code: String, publicKeyHex: String = PUBLIC_KEY_HEX): Parsed? {
        val b = try { unbase32(code) } catch (_: Exception) { return null }
        if (b.size != 73 || b[0].toInt() != 1) return null
        val pub = try { hex(publicKeyHex) } catch (_: Exception) { return null }
        if (pub.size != 32) return null
        val ok = try {
            Ed25519Signer().run {
                init(false, Ed25519PublicKeyParameters(pub, 0))
                update(b, 0, 9)
                verifySignature(b.copyOfRange(9, 73))
            }
        } catch (_: Exception) { false }
        if (!ok) return null
        val day = ((b[7].toInt() and 0xFF) shl 8) or (b[8].toInt() and 0xFF)
        return Parsed(b.copyOfRange(1, 7), day)
    }

    /** Returns null on success, else a short reason. */
    fun activate(ctx: Context, code: String): String? {
        val p = parse(code) ?: return "код недійсний · invalid code"
        if (!p.device.contentEquals(deviceBytes(ctx))) return "код для іншого телефона · code is for another phone"
        if (p.day != FOREVER && untilMs(p.day) < now(ctx)) return "термін коду вже минув · code already expired"
        prefs(ctx).edit().putString("code", code.trim()).apply()
        return null
    }

    private fun untilMs(day: Int) = EPOCH_MS + (day + 1) * DAY_MS    // valid through that whole day

    // ---------- state ----------

    fun state(ctx: Context): State {
        if (!signerOk(ctx)) return State.Expired("змінена збірка · modified build")
        val t = now(ctx)
        prefs(ctx).getString("code", null)?.let { code ->
            val p = parse(code)
            if (p != null && p.device.contentEquals(deviceBytes(ctx))) {
                if (p.day == FOREVER) return State.Active(null)
                val until = untilMs(p.day)
                if (t < until) return State.Active(until)
            }
        }
        val first = prefs(ctx).getLong("first", 0L).takeIf { it > 0 } ?: t.also { prefs(ctx).edit().putLong("first", it).apply() }
        val left = TRIAL_DAYS - ((t - first) / DAY_MS).toInt()
        return if (left > 0) State.Trial(left) else State.Expired("пробний період скінчився · trial ended")
    }

    /** Wall clock, but never earlier than the latest time seen (setting the date back gains nothing). */
    private fun now(ctx: Context): Long {
        val p = prefs(ctx)
        val seen = p.getLong("seen", 0L)
        val t = maxOf(System.currentTimeMillis(), seen)
        if (t - seen > 60_000) p.edit().putLong("seen", t).apply()
        return t
    }

    /** The APK must be signed with the owner's certificate: a rebuilt or repackaged APK is refused. */
    private fun signerOk(ctx: Context): Boolean {
        if (SIGNER_SHA256.isEmpty()) return true
        return try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val certs = info.signingInfo?.apkContentsSigners ?: return false
            certs.any { c ->
                MessageDigest.getInstance("SHA-256").digest(c.toByteArray()).joinToString("") { "%02x".format(it) } == SIGNER_SHA256
            }
        } catch (_: Exception) { false }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- encoding ----------

    private const val ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun base32(b: ByteArray): String {
        val sb = StringBuilder(); var buf = 0; var bits = 0
        for (x in b) {
            buf = (buf shl 8) or (x.toInt() and 0xFF); bits += 8
            while (bits >= 5) { sb.append(ALPHA[(buf shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(ALPHA[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun unbase32(s: String): ByteArray {
        val clean = s.uppercase().filter { it in ALPHA }
        val out = java.io.ByteArrayOutputStream(); var buf = 0; var bits = 0
        for (c in clean) {
            buf = (buf shl 5) or ALPHA.indexOf(c); bits += 5
            if (bits >= 8) { out.write((buf shr (bits - 8)) and 0xFF); bits -= 8 }
        }
        return out.toByteArray()
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Suppress("unused") private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
}
