package top.tianyan.app.core.tools

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Verifies published keys without relying on Android's JCA provider selection. */
internal object PluginSignatureVerifier {
    // RFC 8410 SubjectPublicKeyInfo: Ed25519 OID, absent parameters, 32-byte bit string.
    private val spkiPrefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    fun verify(payload: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        require(publicKey.size == spkiPrefix.size + Ed25519PublicKeyParameters.KEY_SIZE &&
            publicKey.copyOfRange(0, spkiPrefix.size).contentEquals(spkiPrefix)) { "插件目录公钥格式无效" }
        if (signature.size != 64) return false
        return Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(publicKey, spkiPrefix.size))
            update(payload, 0, payload.size)
            verifySignature(signature)
        }
    }
}
