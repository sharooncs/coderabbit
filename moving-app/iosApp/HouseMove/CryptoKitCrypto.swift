import CryptoKit
import Foundation
import HouseMoveKit
import Security

/// The iPhone's crypto for update files, which Android phones open and send (design/encryption.md): P-256 key agreement
/// and AES-256-GCM from CryptoKit, and the phone's own private key kept in the Keychain. Kotlin cannot call CryptoKit, so
/// StartView hands one of these to the shared code at start-up (`MainViewController(buildLabel:crypto:)`), which calls it
/// through the AppleCrypto protocol (sharedUi, AppleCrypto.kt) for exactly what Android's JCA does there. Everything else
/// (the file format, HKDF, nonces) is the shared Kotlin, so both phones write and read the same bytes.
///
/// The private key is a generic password in the Keychain, readable after the first unlock and on this device only: never in
/// a file, never in a backup, never moved to another phone. Kotlin sees only public keys, shared secrets and sealed bytes.
/// The calls come from background threads; CryptoKit and the Keychain are safe to call from any thread.
final class CryptoKitCrypto: NSObject, AppleCrypto {
    func generateKey() -> any AppleKey {
        CryptoKitKey(P256.KeyAgreement.PrivateKey())
    }

    func importKey(raw: Data) -> (any AppleKey)? {
        guard let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: raw) else { return nil }
        return CryptoKitKey(key)
    }

    func isPublicKey(x963: Data) -> Bool {
        (try? P256.KeyAgreement.PublicKey(x963Representation: x963)) != nil
    }

    /// AES-256-GCM with the caller's 12-byte nonce (an update's chunks use "8-byte prefix + chunk number"): the ciphertext,
    /// then the 16-byte tag, as Android's Cipher("AES/GCM/NoPadding") writes it.
    func seal(key: Data, nonce: Data, aad: Data, plain: Data) -> Data? {
        guard key.count == 32, nonce.count == 12,
              let n = try? AES.GCM.Nonce(data: nonce),
              let box = try? AES.GCM.seal(plain, using: SymmetricKey(data: key), nonce: n, authenticating: aad)
        else { return nil }
        return box.ciphertext + box.tag
    }

    func open(key: Data, nonce: Data, aad: Data, sealed: Data) -> Data? {
        guard key.count == 32, nonce.count == 12, sealed.count >= 16,
              let n = try? AES.GCM.Nonce(data: nonce),
              let box = try? AES.GCM.SealedBox(nonce: n, ciphertext: Data(sealed.prefix(sealed.count - 16)), tag: Data(sealed.suffix(16)))
        else { return nil }
        return try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
    }

    /// The key kept under `service` and `account` (the shared code uses the phone's ID), or with `create` a new one kept
    /// there when there is none. A Keychain that cannot be read now (before the first unlock) answers its status and no
    /// key, and nothing is replaced: the shared code tries again later.
    func keychainKey(service: String, account: String, create: Bool) -> any AppleKeychainItem {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var found: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &found)
        if status == errSecSuccess {
            guard let data = found as? Data, let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: data) else {
                return KeychainItem(status: errSecDecode, key: nil)
            }
            return KeychainItem(status: errSecSuccess, key: CryptoKitKey(key))
        }
        guard status == errSecItemNotFound, create else { return KeychainItem(status: status, key: nil) }
        let key = P256.KeyAgreement.PrivateKey()
        let item: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: key.rawRepresentation,
        ]
        let added = SecItemAdd(item as CFDictionary, nil)
        if added == errSecDuplicateItem { return keychainKey(service: service, account: account, create: false) }
        return KeychainItem(status: added, key: added == errSecSuccess ? CryptoKitKey(key) : nil)
    }

    /// Removes every key kept under `service`: on the first start after HouseMove is installed again (the Keychain outlives
    /// the app), and when demo mode or the crypto check ends.
    func keychainForget(service: String) -> Int32 {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        let status = SecItemDelete(query as CFDictionary)
        return status == errSecItemNotFound ? errSecSuccess : status
    }
}

/// A P-256 key pair CryptoKit holds; the private key never leaves it.
final class CryptoKitKey: NSObject, AppleKey {
    private let key: P256.KeyAgreement.PrivateKey
    let publicX963: Data

    init(_ key: P256.KeyAgreement.PrivateKey) {
        self.key = key
        self.publicX963 = key.publicKey.x963Representation
        super.init()
    }

    /// ECDH: the shared secret's raw bytes, the x-coordinate (32 bytes), as Android's KeyAgreement("ECDH") gives it.
    func agree(peerX963: Data) -> Data? {
        guard let peer = try? P256.KeyAgreement.PublicKey(x963Representation: peerX963),
              let secret = try? key.sharedSecretFromKeyAgreement(with: peer)
        else { return nil }
        return secret.withUnsafeBytes { Data($0) }
    }
}

/// What the Keychain said: its OSStatus, and the key when there is one.
final class KeychainItem: NSObject, AppleKeychainItem {
    let status: Int32
    let key: (any AppleKey)?

    init(status: OSStatus, key: (any AppleKey)?) {
        self.status = status
        self.key = key
        super.init()
    }
}
