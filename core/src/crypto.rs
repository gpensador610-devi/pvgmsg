//! Sellado híbrido de mensajes (v1, sin ratchet todavía).
//!
//! Emisor → receptor:
//!   1. ECDH efímero X25519 contra la clave pública del receptor.
//!   2. Encapsulación ML-KEM-768 contra la clave KEM del receptor.
//!   3. clave = HKDF-SHA256( ss_x25519 ‖ ss_mlkem )  → 32 bytes.
//!   4. XChaCha20-Poly1305 con nonce aleatorio de 24 bytes.
//!
//! Formato del mensaje sellado:
//!   [1: versión][32: x25519 efímera pub][1088: mlkem ct][24: nonce][N: ciphertext+tag]

use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use ml_kem::kem::{Decapsulate, Encapsulate};
use ml_kem::{EncodedSizeUser, KemCore, MlKem768};
use rand::rngs::OsRng;
use rand::RngCore;
use sha2::Sha256;
use x25519_dalek::{EphemeralSecret, PublicKey, StaticSecret};
use zeroize::Zeroize;

use crate::error::CoreError;
use crate::identity::{Contact, Identity, MLKEM_SECRET_LEN, X25519_SECRET_LEN};

const SEAL_VERSION: u8 = 1;
const MLKEM_CT_LEN: usize = 1088; // ML-KEM-768 ciphertext
const NONCE_LEN: usize = 24;
const HEADER_LEN: usize = 1 + 32 + MLKEM_CT_LEN + NONCE_LEN;

/// Deriva la clave simétrica combinando ambos secretos compartidos.
fn derive_key(ss_classic: &[u8], ss_pq: &[u8]) -> [u8; 32] {
    let mut ikm = Vec::with_capacity(ss_classic.len() + ss_pq.len());
    ikm.extend_from_slice(ss_classic);
    ikm.extend_from_slice(ss_pq);

    let hk = Hkdf::<Sha256>::new(Some(b"privmsg-v1-hybrid"), &ikm);
    let mut okm = [0u8; 32];
    hk.expand(b"message-key", &mut okm)
        .expect("32 bytes es una longitud válida para HKDF-SHA256");
    ikm.zeroize();
    okm
}

/// Cifra `plaintext` para `recipient`. Devuelve el mensaje sellado autocontenido.
#[uniffi::export]
pub fn seal_message(recipient: Contact, plaintext: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    // 1. ECDH efímero.
    let recipient_x: [u8; 32] =
        recipient
            .x25519_public
            .as_slice()
            .try_into()
            .map_err(|_| CoreError::InvalidKey {
                reason: "clave X25519 del contacto inválida".into(),
            })?;
    let eph_secret = EphemeralSecret::random_from_rng(OsRng);
    let eph_public = PublicKey::from(&eph_secret);
    let ss_classic = eph_secret.diffie_hellman(&PublicKey::from(recipient_x));

    // 2. Encapsulación ML-KEM.
    let ek_arr: ml_kem::Encoded<<MlKem768 as KemCore>::EncapsulationKey> = recipient
        .mlkem_public
        .as_slice()
        .try_into()
        .map_err(|_| CoreError::InvalidKey {
            reason: "clave ML-KEM del contacto inválida".into(),
        })?;
    let ek = <MlKem768 as KemCore>::EncapsulationKey::from_bytes(&ek_arr);
    let (kem_ct, ss_pq) = ek.encapsulate(&mut OsRng).map_err(|_| CoreError::InvalidKey {
        reason: "fallo en encapsulación ML-KEM".into(),
    })?;

    // 3. Clave simétrica combinada.
    let key = derive_key(ss_classic.as_bytes(), ss_pq.as_slice());

    // 4. AEAD.
    let mut nonce = [0u8; NONCE_LEN];
    OsRng.fill_bytes(&mut nonce);
    let cipher = XChaCha20Poly1305::new((&key).into());
    let ciphertext = cipher
        .encrypt(XNonce::from_slice(&nonce), plaintext.as_slice())
        .map_err(|_| CoreError::MalformedMessage {
            reason: "fallo de cifrado AEAD".into(),
        })?;

    let mut sealed = Vec::with_capacity(HEADER_LEN + ciphertext.len());
    sealed.push(SEAL_VERSION);
    sealed.extend_from_slice(eph_public.as_bytes());
    sealed.extend_from_slice(kem_ct.as_slice());
    sealed.extend_from_slice(&nonce);
    sealed.extend_from_slice(&ciphertext);
    Ok(sealed)
}

/// Descifra un mensaje sellado dirigido a `identity`.
#[uniffi::export]
pub fn open_message(identity: Identity, sealed: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    if sealed.len() < HEADER_LEN + 16 {
        return Err(CoreError::MalformedMessage {
            reason: format!("demasiado corto: {} bytes", sealed.len()),
        });
    }
    if sealed[0] != SEAL_VERSION {
        return Err(CoreError::MalformedMessage {
            reason: format!("versión {} no soportada", sealed[0]),
        });
    }

    let eph_pub: [u8; 32] = sealed[1..33].try_into().unwrap();
    let kem_ct = &sealed[33..33 + MLKEM_CT_LEN];
    let nonce = &sealed[33 + MLKEM_CT_LEN..HEADER_LEN];
    let ciphertext = &sealed[HEADER_LEN..];

    // 1. ECDH con nuestra clave estática.
    if identity.x25519_secret.len() != X25519_SECRET_LEN {
        return Err(CoreError::InvalidKey {
            reason: "secreto X25519 propio inválido".into(),
        });
    }
    let x_secret_bytes: [u8; 32] = identity.x25519_secret.as_slice().try_into().unwrap();
    let x_secret = StaticSecret::from(x_secret_bytes);
    let ss_classic = x_secret.diffie_hellman(&PublicKey::from(eph_pub));

    // 2. Decapsulación ML-KEM.
    if identity.mlkem_secret.len() != MLKEM_SECRET_LEN {
        return Err(CoreError::InvalidKey {
            reason: "secreto ML-KEM propio inválido".into(),
        });
    }
    let dk_arr: ml_kem::Encoded<<MlKem768 as KemCore>::DecapsulationKey> = identity
        .mlkem_secret
        .as_slice()
        .try_into()
        .map_err(|_| CoreError::InvalidKey {
            reason: "secreto ML-KEM no decodificable".into(),
        })?;
    let dk = <MlKem768 as KemCore>::DecapsulationKey::from_bytes(&dk_arr);
    let ct_arr: ml_kem::Ciphertext<MlKem768> =
        kem_ct.try_into().map_err(|_| CoreError::MalformedMessage {
            reason: "ciphertext ML-KEM inválido".into(),
        })?;
    let ss_pq = dk
        .decapsulate(&ct_arr)
        .map_err(|_| CoreError::DecryptionFailed)?;

    // 3 + 4. Derivar clave y abrir AEAD.
    let key = derive_key(ss_classic.as_bytes(), ss_pq.as_slice());
    let cipher = XChaCha20Poly1305::new((&key).into());
    cipher
        .decrypt(XNonce::from_slice(nonce), ciphertext)
        .map_err(|_| CoreError::DecryptionFailed)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::generate_identity;

    fn contact_of(id: &Identity) -> Contact {
        Contact {
            x25519_public: id.x25519_public.clone(),
            mlkem_public: id.mlkem_public.clone(),
            fingerprint: id.fingerprint.clone(),
        }
    }

    #[test]
    fn seal_open_roundtrip() {
        let alice = generate_identity();
        let bob = generate_identity();

        let msg = "hola bob, esto es secreto 🤫".as_bytes().to_vec();
        let sealed = seal_message(contact_of(&bob), msg.clone()).unwrap();
        assert_ne!(&sealed[HEADER_LEN..], msg.as_slice());

        let opened = open_message(bob.clone(), sealed.clone()).unwrap();
        assert_eq!(opened, msg);

        // Alice no puede abrir un mensaje para Bob.
        assert!(open_message(alice, sealed).is_err());
    }

    #[test]
    fn tampered_message_fails() {
        let bob = generate_identity();
        let mut sealed = seal_message(contact_of(&bob), b"integridad".to_vec()).unwrap();
        let last = sealed.len() - 1;
        sealed[last] ^= 0x01;
        assert!(open_message(bob, sealed).is_err());
    }

    #[test]
    fn each_seal_is_unique() {
        let bob = generate_identity();
        let a = seal_message(contact_of(&bob), b"mismo texto".to_vec()).unwrap();
        let b = seal_message(contact_of(&bob), b"mismo texto".to_vec()).unwrap();
        assert_ne!(a, b, "claves efímeras y nonce deben diferir siempre");
    }
}
