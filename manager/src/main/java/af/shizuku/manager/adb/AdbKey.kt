package af.shizuku.manager.adb

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import rikka.core.ktx.unsafeLazy
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

private const val TAG = "AdbKey"

class AdbKey(
    private val adbKeyStore: AdbKeyStore,
    name: String,
) {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ENCRYPTION_KEY_ALIAS = "_adbkey_encryption_key_"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        private const val IV_SIZE_IN_BYTES = 12
        private const val TAG_SIZE_IN_BYTES = 16

        private val PADDING =
            byteArrayOf(
                0x00,
                0x01,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                0x00,
                0x30,
                0x21,
                0x30,
                0x09,
                0x06,
                0x05,
                0x2b,
                0x0e,
                0x03,
                0x02,
                0x1a,
                0x05,
                0x00,
                0x04,
                0x14,
            )
    }

    private val encryptionKey: Key?

    private val privateKey: RSAPrivateKey
    private val publicKey: RSAPublicKey
    private val certificate: X509Certificate

    init {
        val encryptionKeyMaybe = getOrCreateEncryptionKey()
        if (encryptionKeyMaybe == null) {
            Timber.tag(TAG).w("AndroidKeyStore is unusable. Falling back to software-backed (BouncyCastle) key storage.")
            this.encryptionKey = null
        } else {
            this.encryptionKey = encryptionKeyMaybe
        }

        this.privateKey = getOrCreatePrivateKey()
        this.publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4)) as RSAPublicKey

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val x509Certificate =
            X509v3CertificateBuilder(
                X500Name("CN=00"),
                BigInteger.ONE,
                Date(0),
                Date(2461449600 * 1000),
                Locale.ROOT,
                X500Name("CN=00"),
                SubjectPublicKeyInfo.getInstance(publicKey.encoded),
            ).build(signer)
        this.certificate =
            CertificateFactory
                .getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(x509Certificate.encoded)) as X509Certificate
    }

    /** MD5 of the public key blob as colon-separated hex, the form adbd's dialog displays. */
    fun fingerprint(): String {
        val base64 = String(adbPublicKey).substringBefore(' ').trim { it <= ' ' }
        val digest =
            java.security.MessageDigest
                .getInstance("MD5")
                .digest(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
        return digest.joinToString(":") { "%02X".format(it) }
    }

    val adbPublicKey: ByteArray by unsafeLazy {
        publicKey.adbEncoded(name)
    }

    private fun getOrCreateEncryptionKey(): Key? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        try {
            keyStore.load(null)
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to load KeyStore: ${e.message}")
            return null
        }

        val key =
            try {
                keyStore.getKey(ENCRYPTION_KEY_ALIAS, null)
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to get key from KeyStore (common on Samsung), attempting reset: ${e.message}")
                try {
                    keyStore.deleteEntry(ENCRYPTION_KEY_ALIAS)
                } catch (_: Exception) {
                }
                null
            }

        return key ?: run {
            try {
                val parameterSpec =
                    KeyGenParameterSpec
                        .Builder(ENCRYPTION_KEY_ALIAS, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                keyGenerator.init(parameterSpec)
                keyGenerator.generateKey()
            } catch (e: Exception) {
                // Samsung Knox or Auto Blocker can throw ProviderException or generic Exception here
                Timber.tag(TAG).w("Failed to generate encryption key in KeyStore. Device might have restricted KeyStore access: ${e.message}")
                null
            }
        }
    }

    private fun encrypt(
        plaintext: ByteArray,
        aad: ByteArray?,
    ): ByteArray? {
        if (encryptionKey == null) {
            return plaintext
        }

        if (plaintext.size > Int.MAX_VALUE - IV_SIZE_IN_BYTES - TAG_SIZE_IN_BYTES) {
            return null
        }
        val ciphertext = ByteArray(IV_SIZE_IN_BYTES + plaintext.size + TAG_SIZE_IN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        cipher.updateAAD(aad)
        cipher.doFinal(plaintext, 0, plaintext.size, ciphertext, IV_SIZE_IN_BYTES)
        System.arraycopy(cipher.iv, 0, ciphertext, 0, IV_SIZE_IN_BYTES)
        return ciphertext
    }

    private fun decrypt(
        ciphertext: ByteArray,
        aad: ByteArray?,
    ): ByteArray? {
        if (encryptionKey == null) {
            return ciphertext
        }

        if (ciphertext.size < IV_SIZE_IN_BYTES + TAG_SIZE_IN_BYTES) {
            return null
        }
        val params = GCMParameterSpec(8 * TAG_SIZE_IN_BYTES, ciphertext, 0, IV_SIZE_IN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, params)
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext, IV_SIZE_IN_BYTES, ciphertext.size - IV_SIZE_IN_BYTES)
    }

    private fun getOrCreatePrivateKey(): RSAPrivateKey {
        var privateKey: RSAPrivateKey? = null

        val aad = ByteArray(16)
        "adbkey".toByteArray().copyInto(aad)

        var ciphertext = adbKeyStore.get()
        if (ciphertext != null) {
            try {
                val plaintext = decrypt(ciphertext, aad) ?: throw IllegalStateException("Decryption failed")

                val keyFactory =
                    if (encryptionKey == null) {
                        Security.addProvider(BouncyCastleProvider())
                        KeyFactory.getInstance("RSA", "BC")
                    } else {
                        KeyFactory.getInstance("RSA")
                    }
                privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(plaintext)) as RSAPrivateKey
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to decrypt stored ADB key; generating a new key: ${e.message}")
            }
        }
        if (privateKey == null) {
            val keyPairGenerator =
                try {
                    if (encryptionKey == null) throw ProviderException("Force fallback")
                    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Using BouncyCastle for RSA generation: ${e.message}")
                    Security.addProvider(BouncyCastleProvider())
                    KeyPairGenerator.getInstance("RSA", "BC")
                }

            keyPairGenerator.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            val keyPair = keyPairGenerator.generateKeyPair()
            privateKey = keyPair.private as RSAPrivateKey

            ciphertext = encrypt(privateKey.encoded, aad)
            if (ciphertext != null) {
                adbKeyStore.put(ciphertext)
            }
        }
        return privateKey
    }

    fun sign(data: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(PADDING)
        return cipher.doFinal(data)
    }

    private val keyManager
        get() =
            object : X509ExtendedKeyManager() {
                private val alias = "key"

                override fun chooseClientAlias(
                    keyTypes: Array<out String>,
                    issuers: Array<out Principal>?,
                    socket: Socket?,
                ): String? {
                    Timber.tag(TAG).d("chooseClientAlias: keyType=${keyTypes.contentToString()}, issuers=${issuers?.contentToString()}")
                    for (keyType in keyTypes) {
                        if (keyType == "RSA") return alias
                    }
                    return null
                }

                override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
                    Timber.tag(TAG).d("getCertificateChain: alias=$alias")
                    return if (alias == this.alias) arrayOf(certificate) else null
                }

                override fun getPrivateKey(alias: String?): PrivateKey? {
                    Timber.tag(TAG).d("getPrivateKey: alias=$alias")
                    return if (alias == this.alias) privateKey else null
                }

                override fun getClientAliases(
                    keyType: String?,
                    issuers: Array<out Principal>?,
                ): Array<String>? = null

                override fun getServerAliases(
                    keyType: String,
                    issuers: Array<out Principal>?,
                ): Array<String>? = null

                override fun chooseServerAlias(
                    keyType: String,
                    issuers: Array<out Principal>?,
                    socket: Socket?,
                ): String? = null
            }

    fun sslContext(trustManager: X509ExtendedTrustManager): SSLContext =
        SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        }
}

interface AdbKeyStore {
    fun put(bytes: ByteArray)

    fun get(): ByteArray?
}

class PreferenceAdbKeyStore(
    private val preference: SharedPreferences,
) : AdbKeyStore {
    private val preferenceKey = "adbkey"

    override fun put(bytes: ByteArray) {
        preference.edit { putString(preferenceKey, String(Base64.encode(bytes, Base64.NO_WRAP))) }
    }

    override fun get(): ByteArray? {
        if (!preference.contains(preferenceKey)) return null
        return Base64.decode(preference.getString(preferenceKey, null), Base64.NO_WRAP)
    }
}

const val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8
const val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4
const val RSAPublicKey_Size = 524

private fun BigInteger.toAdbEncoded(): IntArray {
    // little-endian integer with padding zeros in the end

    val endcoded = IntArray(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    val r32 = BigInteger.ZERO.setBit(32)

    var tmp = this.add(BigInteger.ZERO)
    for (i in 0 until ANDROID_PUBKEY_MODULUS_SIZE_WORDS) {
        val out = tmp.divideAndRemainder(r32)
        tmp = out[0]
        endcoded[i] = out[1].toInt()
    }
    return endcoded
}

private fun RSAPublicKey.adbEncoded(name: String): ByteArray {
    // https://cs.android.com/android/platform/superproject/+/android-10.0.0_r30:system/core/libcrypto_utils/android_pubkey.c

    /*
    typedef struct RSAPublicKey {
        uint32_t modulus_size_words; // ANDROID_PUBKEY_MODULUS_SIZE
        uint32_t n0inv; // n0inv = -1 / N[0] mod 2^32
        uint8_t modulus[ANDROID_PUBKEY_MODULUS_SIZE];
        uint8_t rr[ANDROID_PUBKEY_MODULUS_SIZE]; // rr = (2^(rsa_size)) ^ 2 mod N
        uint32_t exponent;
    } RSAPublicKey;
     */

    val r32 = BigInteger.ZERO.setBit(32)
    val n0inv = modulus.remainder(r32).modInverse(r32).negate()
    val r = BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8)
    val rr = r.modPow(BigInteger.valueOf(2), modulus)

    val buffer = ByteBuffer.allocate(RSAPublicKey_Size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    buffer.putInt(n0inv.toInt())
    modulus.toAdbEncoded().forEach { buffer.putInt(it) }
    rr.toAdbEncoded().forEach { buffer.putInt(it) }
    buffer.putInt(publicExponent.toInt())

    val base64Bytes = Base64.encode(buffer.array(), Base64.NO_WRAP)
    val nameBytes = " $name\u0000".toByteArray()
    val bytes = ByteArray(base64Bytes.size + nameBytes.size)
    base64Bytes.copyInto(bytes)
    nameBytes.copyInto(bytes, base64Bytes.size)
    return bytes
}

fun parseAdbPublicKey(keyStr: String): RSAPublicKey {
    val base64 = keyStr.substringBefore(' ')
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val modulusSizeWords = buffer.int
    buffer.int
    val modulusInts = IntArray(modulusSizeWords)
    for (i in 0 until modulusSizeWords) {
        modulusInts[i] = buffer.int
    }
    val rrInts = IntArray(modulusSizeWords)
    for (i in 0 until modulusSizeWords) {
        rrInts[i] = buffer.int
    }
    val exponent = buffer.int

    var modulus = BigInteger.ZERO
    val r32 = BigInteger.ZERO.setBit(32)
    for (i in modulusInts.indices.reversed()) {
        modulus = modulus.multiply(r32).add(BigInteger.valueOf(modulusInts[i].toLong() and 0xFFFFFFFFL))
    }

    val spec = RSAPublicKeySpec(modulus, BigInteger.valueOf(exponent.toLong() and 0xFFFFFFFFL))
    return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
}
