package top.tianyan.app.core.tools

import java.security.Key
import java.security.KeyFactory
import java.security.KeyFactorySpi
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.Security
import java.security.spec.KeySpec
import java.security.spec.X509EncodedKeySpec
import org.junit.Assert.*
import org.junit.Test

class PluginSignatureVerifierTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    // RFC 8032 section 7.1, test 1 (empty message).
    private val key = hex("302a300506032b6570032100d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val signature = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
        "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")

    @Test fun verifiesRfc8032VectorAndRejectsTampering() {
        assertTrue(PluginSignatureVerifier.verify(byteArrayOf(), signature, key))
        assertFalse(PluginSignatureVerifier.verify(byteArrayOf(1), signature, key))
        assertFalse(PluginSignatureVerifier.verify(byteArrayOf(), signature.copyOf().apply { this[0] = 0 }, key))
        assertFalse(PluginSignatureVerifier.verify(byteArrayOf(), signature.copyOf(63), key))
        assertFalse(PluginSignatureVerifier.verify(byteArrayOf(), signature.copyOf(65), key))
        val other = hex("302a300506032b65700321003d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        assertFalse(PluginSignatureVerifier.verify(byteArrayOf(), signature, other))
    }

    @Test fun rejectsIncorrectSpkiAlgorithmAndLengths() {
        listOf(key.copyOf(43), key + byteArrayOf(0), key.copyOf().apply { this[8] = 0x71 }).forEach {
            assertThrows(IllegalArgumentException::class.java) { PluginSignatureVerifier.verify(byteArrayOf(), signature, it) }
        }
    }

    @Test fun verifiesWhenPreferredJcaProviderRejectsImportedKeys() {
        val provider = object : Provider("RejectImportedEd25519", 1.0, "Reproduces AndroidKeyStore public-key import failure") {
            init { put("KeyFactory.Ed25519", RejectImportedKeyFactory::class.java.name) }
        }
        Security.insertProviderAt(provider, 1)
        try {
            assertThrows(UnsupportedOperationException::class.java) {
                KeyFactory.getInstance("Ed25519", provider).generatePublic(X509EncodedKeySpec(key))
            }
            assertTrue(PluginSignatureVerifier.verify(byteArrayOf(), signature, key))
        } finally { Security.removeProvider(provider.name) }
    }

    class RejectImportedKeyFactory : KeyFactorySpi() {
        override fun engineGeneratePublic(spec: KeySpec): PublicKey = throw UnsupportedOperationException(
            "To generate a key pair in Android Keystore, use KeyPairGenerator initialized with android.security.keystore.KeyGenParameterSpec")
        override fun engineGeneratePrivate(spec: KeySpec): PrivateKey = error("Unused")
        override fun <T : KeySpec> engineGetKeySpec(key: Key, spec: Class<T>): T = error("Unused")
        override fun engineTranslateKey(key: Key): Key = error("Unused")
    }
}
