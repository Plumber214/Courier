package courier.link

import courier.platform.getPlatformActions
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * @param storageDirOverride where the keypair and device id live. Defaults to
 *   the app storage directory; supplied explicitly only by tests, which need
 *   two independent identities in one process to exercise a real handshake.
 */
class CertificateStore(storageDirOverride: File? = null) {

    val deviceId: String
    val certificate: X509Certificate
    val privateKey: PrivateKey
    val certificateSha256: String
    val certificateBase64: String

    private val nameFile: File
    private val keyStore: KeyStore

    fun getDeviceName(): String {
        return if (nameFile.exists() && nameFile.length() > 0) {
            nameFile.readText(Charsets.UTF_8).trim()
        } else {
            getPlatformActions().getDefaultDeviceName()
        }
    }

    fun setDeviceName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotBlank()) {
            nameFile.writeText(trimmed, Charsets.UTF_8)
        }
    }

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }

        val storageDir = storageDirOverride ?: File(getPlatformActions().getAppStorageDirectory())
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }

        val keyStoreFile = File(storageDir, KEYSTORE_FILENAME)
        val idFile = File(storageDir, IDENTITY_FILENAME)
        nameFile = File(storageDir, NAME_FILENAME)

        val id = if (idFile.exists() && idFile.length() > 0) {
            idFile.readText(Charsets.UTF_8).trim()
        } else {
            val newId = UUID.randomUUID().toString().replace("-", "_")
            idFile.writeText(newId, Charsets.UTF_8)
            newId
        }
        deviceId = id

        val ks = KeyStore.getInstance("PKCS12")
        val password = KEYSTORE_PASSWORD.toCharArray()

        if (keyStoreFile.exists() && keyStoreFile.length() > 0) {
            FileInputStream(keyStoreFile).use { fis ->
                ks.load(fis, password)
            }
            keyStore = ks
            certificate = ks.getCertificate(ALIAS) as X509Certificate
            privateKey = ks.getKey(ALIAS, password) as PrivateKey
        } else {
            val keyPair = generateKeyPair()
            val cert = generateCertificate(id, keyPair)
            ks.load(null, password)
            ks.setKeyEntry(ALIAS, keyPair.private, password, arrayOf(cert))
            FileOutputStream(keyStoreFile).use { fos ->
                ks.store(fos, password)
            }
            keyStore = ks
            certificate = cert
            privateKey = keyPair.private
        }

        certificateSha256 = computeSha256(certificate.encoded)
        certificateBase64 = Base64.getEncoder().encodeToString(certificate.encoded)
    }

    private fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        return kpg.generateKeyPair()
    }

    private fun generateCertificate(id: String, keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24 * 60 * 60 * 1000L)
        val notAfter = Date(now + 25L * 365 * 24 * 60 * 60 * 1000L) // 25 years
        val serialNumber = BigInteger(64, SecureRandom())
        val subject = X500Name("CN=$id, O=Project Courier")

        val builder = JcaX509v3CertificateBuilder(
            subject,
            serialNumber,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certHolder = builder.build(signer)
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        return certFactory.generateCertificate(java.io.ByteArrayInputStream(certHolder.encoded)) as X509Certificate
    }

    fun computeVerificationCode(peerCertSha256: String): String {
        val combined = listOf(certificateSha256, peerCertSha256).sorted().joinToString(":")
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(combined.toByteArray(Charsets.UTF_8))
        val hex = hash.take(4).joinToString("") { "%02X".format(it) } // 8 hex chars
        return "${hex.substring(0, 4)}-${hex.substring(4, 8)}"
    }

    fun createSslContext(peerCertValidator: (X509Certificate) -> Boolean): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, KEYSTORE_PASSWORD.toCharArray())

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw CertificateException("Empty certificate chain")
                val cert = chain[0]
                if (!peerCertValidator(cert)) {
                    throw CertificateException("Peer certificate rejected by TrustManager")
                }
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw CertificateException("Empty certificate chain")
                val cert = chain[0]
                if (!peerCertValidator(cert)) {
                    throw CertificateException("Peer certificate rejected by TrustManager")
                }
            }

            /**
             * Deliberately empty, and it must stay that way.
             *
             * On the side acting as TLS server this list becomes the
             * certificate_authorities of the CertificateRequest. Returning our
             * own certificate advertised "only certs issued by me are
             * acceptable" — and since every device here is self-signed, the
             * peer's KeyManager could never find a match. It sent an empty
             * chain, and the handshake died with certificate_required.
             *
             * Empty means "no issuer constraint", so the peer offers its own
             * self-signed certificate. Trust is not weakened by this: identity
             * is decided by the pinning check in checkClientTrusted /
             * checkServerTrusted above, not by issuer.
             */
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(kmf.keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
        return sslContext
    }

    companion object {
        private const val KEYSTORE_FILENAME = "courier_identity.p12"
        private const val IDENTITY_FILENAME = "courier_device_id.txt"
        private const val NAME_FILENAME = "courier_device_name.txt"
        private const val KEYSTORE_PASSWORD = "courier_internal_link_key"
        private const val ALIAS = "courier_identity"

        fun computeSha256(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}