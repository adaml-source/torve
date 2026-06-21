import Foundation
import CryptoKit
import shared

/// CryptoKit-backed iOS implementation of the shared
/// `TransferCryptoEngine` interface used by the credential-transfer
/// flow.
///
/// Wire-format compatibility with the desktop/Android engines is
/// load-bearing — envelopes round-trip across platforms. Specifically:
///
///   * X25519 public keys are the **raw 32-byte** RFC 7748 §5
///     little-endian u-coordinate. CryptoKit's
///     `Curve25519.KeyAgreement.PublicKey.rawRepresentation` matches.
///   * X25519 private keys are opaque per-platform bytes (we never
///     ship them on the wire). iOS uses CryptoKit's 32-byte raw
///     `rawRepresentation`.
///   * HKDF uses SHA-256, **zero-salt** (32 bytes of `0x00` per
///     RFC 5869 §2.2), and the same `info` byte string the protocol
///     layer hands in (`SHARED_KEY_INFO = "torve-secrets-transfer-v1"`).
///   * AEAD is AES-256-GCM with a 12-byte nonce and a 16-byte tag.
///   * `encryptAead` returns `ciphertext || tag` — NOT CryptoKit's
///     `combined` (which is `nonce || ciphertext || tag`). The protocol
///     stores the nonce separately, so we strip CryptoKit's nonce
///     prefix and concatenate body+tag the way the JDK Cipher does on
///     desktop and the platform Cipher does on Android.
///
/// No private-key material is logged or persisted; CryptoKit holds it
/// in a `SymmetricKey` / `Curve25519` value type that's released when
/// the call frame goes out of scope.
final class IOSTransferCryptoEngine: TransferCryptoEngine {

    func generateEphemeralKeyPair() async throws -> EphemeralKeyPair {
        let priv = Curve25519.KeyAgreement.PrivateKey()
        let publicData = priv.publicKey.rawRepresentation
        let privateData = priv.rawRepresentation
        return EphemeralKeyPair(
            publicKey: KotlinByteArray.from(publicData),
            privateKey: KotlinByteArray.from(privateData)
        )
    }

    func deriveSharedKey(
        privateKey: KotlinByteArray,
        peerPublicKey: KotlinByteArray,
        info: KotlinByteArray
    ) async throws -> KotlinByteArray {
        let privData = privateKey.toData()
        let peerData = peerPublicKey.toData()
        let infoData = info.toData()

        let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privData)
        let peer = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerData)
        let shared = try priv.sharedSecretFromKeyAgreement(with: peer)

        // Zero-salt is the RFC 5869 §2.2 default and matches the desktop
        // and Android engines' explicit 32-byte zero salt input.
        let salt = Data(count: 32)
        let derived = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: salt,
            sharedInfo: infoData,
            outputByteCount: 32
        )
        let raw = derived.withUnsafeBytes { Data($0) }
        return KotlinByteArray.from(raw)
    }

    func encryptAead(
        key: KotlinByteArray,
        nonce: KotlinByteArray,
        plaintext: KotlinByteArray,
        associatedData: KotlinByteArray
    ) async throws -> KotlinByteArray {
        let symmetricKey = SymmetricKey(data: key.toData())
        let aesNonce = try AES.GCM.Nonce(data: nonce.toData())
        let sealed = try AES.GCM.seal(
            plaintext.toData(),
            using: symmetricKey,
            nonce: aesNonce,
            authenticating: associatedData.toData()
        )
        // Match desktop/Android: ciphertext-body || 16-byte tag.
        var out = Data()
        out.append(sealed.ciphertext)
        out.append(sealed.tag)
        return KotlinByteArray.from(out)
    }

    func decryptAead(
        key: KotlinByteArray,
        nonce: KotlinByteArray,
        ciphertext: KotlinByteArray,
        associatedData: KotlinByteArray
    ) async throws -> KotlinByteArray? {
        let cipherData = ciphertext.toData()
        guard cipherData.count >= 16 else { return nil }
        let body = cipherData.prefix(cipherData.count - 16)
        let tag = cipherData.suffix(16)

        do {
            let symmetricKey = SymmetricKey(data: key.toData())
            let aesNonce = try AES.GCM.Nonce(data: nonce.toData())
            let sealed = try AES.GCM.SealedBox(nonce: aesNonce, ciphertext: body, tag: tag)
            let plaintext = try AES.GCM.open(
                sealed,
                using: symmetricKey,
                authenticating: associatedData.toData()
            )
            return KotlinByteArray.from(plaintext)
        } catch {
            // AEAD verification failed (or input was malformed). Mirror
            // the desktop engine's `runCatching { ... }.getOrNull()`.
            return nil
        }
    }

    func secureRandom(byteCount: Int32) async throws -> KotlinByteArray {
        precondition(byteCount >= 0, "byteCount must be non-negative")
        var data = Data(count: Int(byteCount))
        let status = data.withUnsafeMutableBytes { ptr -> Int32 in
            guard let base = ptr.baseAddress else { return errSecParam }
            return SecRandomCopyBytes(kSecRandomDefault, Int(byteCount), base)
        }
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed: \(status)")
        return KotlinByteArray.from(data)
    }
}

// MARK: - KotlinByteArray <-> Data bridging

private extension KotlinByteArray {
    func toData() -> Data {
        let count = Int(self.size)
        var data = Data(count: count)
        for i in 0..<count {
            data[i] = UInt8(bitPattern: self.get(index: Int32(i)))
        }
        return data
    }

    static func from(_ data: Data) -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(data.count))
        for (i, byte) in data.enumerated() {
            array.set(index: Int32(i), value: Int8(bitPattern: byte))
        }
        return array
    }
}
