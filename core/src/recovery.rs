//! Frase de recuperación de 12 palabras (BIP-39), el mismo estándar que usan
//! las wallets descentralizadas.
//!
//! La identidad deja de ser aleatoria: se **deriva determinísticamente** de la
//! semilla. Las mismas 12 palabras producen siempre exactamente las mismas
//! claves, así que puedes cambiar de teléfono conservando tu huella y que tus
//! contactos te sigan reconociendo.
//!
//! ```text
//! 12 palabras ──BIP39──> semilla 64 B ──HKDF──> ┬─> secreto X25519 (32 B)
//!                                               ├─> d ML-KEM     (32 B)
//!                                               └─> z ML-KEM     (32 B)
//! ```
//!
//! Ojo: la frase recupera *quién eres*, no *lo que tienes*. Los contactos y el
//! historial viven solo en el dispositivo (ver copia de seguridad cifrada).

use bip39::{Language, Mnemonic};
use hkdf::Hkdf;
use ml_kem::{EncodedSizeUser, KemCore, MlKem768};
use rand::rngs::OsRng;
use rand::RngCore;
use sha2::Sha256;
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroize;

use crate::error::CoreError;
use crate::identity::{fingerprint_of, Identity};

/// Genera una frase de recuperación nueva de 12 palabras (128 bits de entropía).
#[uniffi::export]
pub fn generate_mnemonic() -> String {
    let mut entropy = [0u8; 16];
    OsRng.fill_bytes(&mut entropy);
    let mnemonic = Mnemonic::from_entropy_in(Language::English, &entropy)
        .expect("16 bytes de entropía son válidos para 12 palabras");
    entropy.zeroize();
    mnemonic.to_string()
}

/// Valida una frase sin derivar claves (para dar feedback en la UI).
#[uniffi::export]
pub fn validate_mnemonic(phrase: String) -> bool {
    Mnemonic::parse_in_normalized(Language::English, phrase.trim()).is_ok()
}

/// Deriva la identidad completa a partir de la frase de recuperación.
///
/// Determinista: la misma frase produce siempre las mismas claves.
#[uniffi::export]
pub fn identity_from_mnemonic(phrase: String) -> Result<Identity, CoreError> {
    let mnemonic = Mnemonic::parse_in_normalized(Language::English, phrase.trim()).map_err(|e| {
        CoreError::InvalidMnemonic {
            reason: format!("{e}"),
        }
    })?;

    // Semilla BIP-39 estándar (sin passphrase adicional).
    let mut seed = mnemonic.to_seed_normalized("");
    let hk = Hkdf::<Sha256>::new(Some(b"privmsg-v1-identity"), &seed);
    seed.zeroize();

    // 1. Secreto X25519.
    let mut x_secret_bytes = [0u8; 32];
    hk.expand(b"x25519", &mut x_secret_bytes)
        .expect("32 bytes válidos");
    let x_secret = StaticSecret::from(x_secret_bytes);
    let x_public = PublicKey::from(&x_secret);
    x_secret_bytes.zeroize();

    // 2. Semillas d/z de ML-KEM-768 (FIPS 203 permite keygen determinista).
    let mut d_bytes = [0u8; 32];
    let mut z_bytes = [0u8; 32];
    hk.expand(b"ml-kem-d", &mut d_bytes).expect("32 bytes válidos");
    hk.expand(b"ml-kem-z", &mut z_bytes).expect("32 bytes válidos");

    let d = ml_kem::B32::from(d_bytes);
    let z = ml_kem::B32::from(z_bytes);
    // (B32 y generate_deterministic requieren la feature `deterministic` de ml-kem)
    let (kem_dk, kem_ek) = MlKem768::generate_deterministic(&d, &z);
    d_bytes.zeroize();
    z_bytes.zeroize();

    let mlkem_public = kem_ek.as_bytes().to_vec();
    let fingerprint = fingerprint_of(x_public.as_bytes().as_slice(), &mlkem_public);

    Ok(Identity {
        x25519_secret: x_secret.to_bytes().to_vec(),
        x25519_public: x_public.as_bytes().to_vec(),
        mlkem_secret: kem_dk.as_bytes().to_vec(),
        mlkem_public,
        fingerprint,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mnemonic_has_twelve_words_and_validates() {
        let phrase = generate_mnemonic();
        assert_eq!(phrase.split_whitespace().count(), 12);
        assert!(validate_mnemonic(phrase));
    }

    #[test]
    fn derivation_is_deterministic() {
        let phrase = generate_mnemonic();
        let a = identity_from_mnemonic(phrase.clone()).unwrap();
        let b = identity_from_mnemonic(phrase).unwrap();

        assert_eq!(a.fingerprint, b.fingerprint, "misma frase → misma identidad");
        assert_eq!(a.x25519_secret, b.x25519_secret);
        assert_eq!(a.mlkem_secret, b.mlkem_secret);
        assert_eq!(a.mlkem_public, b.mlkem_public);
    }

    #[test]
    fn different_phrases_give_different_identities() {
        let a = identity_from_mnemonic(generate_mnemonic()).unwrap();
        let b = identity_from_mnemonic(generate_mnemonic()).unwrap();
        assert_ne!(a.fingerprint, b.fingerprint);
    }

    #[test]
    fn recovered_identity_can_decrypt() {
        use crate::crypto::{open_message, seal_message};
        use crate::identity::Contact;

        let phrase = generate_mnemonic();
        let original = identity_from_mnemonic(phrase.clone()).unwrap();

        // Alguien le escribe usando su clave pública.
        let sealed = seal_message(
            Contact {
                x25519_public: original.x25519_public.clone(),
                mlkem_public: original.mlkem_public.clone(),
                fingerprint: original.fingerprint.clone(),
            },
            b"mensaje enviado antes de cambiar de telefono".to_vec(),
        )
        .unwrap();

        // Restaura en un "teléfono nuevo" y lo abre.
        let restored = identity_from_mnemonic(phrase).unwrap();
        let opened = open_message(restored, sealed).unwrap();
        assert_eq!(opened, b"mensaje enviado antes de cambiar de telefono");
    }

    #[test]
    fn rejects_invalid_phrases() {
        assert!(!validate_mnemonic("esto no es una frase valida".into()));
        assert!(identity_from_mnemonic("palabras inventadas aqui".into()).is_err());
        // 12 palabras del diccionario pero con checksum incorrecto.
        assert!(!validate_mnemonic(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon".into()
        ));
    }
}
