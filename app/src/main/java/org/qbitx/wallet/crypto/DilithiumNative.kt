package org.qbitx.wallet.crypto

/**
 * JNI bridge to the Dilithium3 reference C implementation.
 * All crypto operations happen in native code for security and performance.
 */
class DilithiumNative {
    companion object {
        init {
            System.loadLibrary("qbitx_crypto")
        }
    }

    /** Generate a Dilithium3 keypair. Returns [publicKey, secretKey] as byte arrays. */
    external fun generateKeypair(): Array<ByteArray>?

    /** Sign a message with the secret key. Returns the detached signature. */
    external fun sign(message: ByteArray, secretKey: ByteArray): ByteArray?

    /** Verify a detached signature against a message and public key. */
    external fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean

    external fun getPublicKeyBytes(): Int
    external fun getSecretKeyBytes(): Int
    external fun getSignatureBytes(): Int
}
