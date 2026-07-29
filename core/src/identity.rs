use ml_kem::{EncodedSizeUser, KemCore, MlKem768};
use rand::rngs::OsRng;
use sha2::{Digest, Sha256};
use x25519_dalek::{PublicKey, StaticSecret};

use crate::error::CoreError;

/// Tamaños de serialización (bytes).
pub const X25519_PUBLIC_LEN: usize = 32;
pub const X25519_SECRET_LEN: usize = 32;
pub const MLKEM_PUBLIC_LEN: usize = 1184; // ML-KEM-768 encapsulation key
pub const MLKEM_SECRET_LEN: usize = 2400; // ML-KEM-768 decapsulation key

/// Identidad local completa (incluye claves secretas).
///
/// Los secretos solo cruzan la FFI dentro del proceso de la app; en Android se
/// persisten cifrados (EncryptedSharedPreferences respaldado por el Keystore).
#[derive(uniffi::Record, Clone)]
pub struct Identity {
    pub x25519_secret: Vec<u8>,
    pub x25519_public: Vec<u8>,
    pub mlkem_secret: Vec<u8>,
    pub mlkem_public: Vec<u8>,
    /// Huella legible por humanos para verificación fuera de banda.
    pub fingerprint: String,
}

/// Parte pública de la identidad de un contacto.
#[derive(uniffi::Record, Clone)]
pub struct Contact {
    pub x25519_public: Vec<u8>,
    pub mlkem_public: Vec<u8>,
    pub fingerprint: String,
}

/// Genera una identidad híbrida nueva con el RNG del sistema operativo.
#[uniffi::export]
pub fn generate_identity() -> Identity {
    let x_secret = StaticSecret::random_from_rng(OsRng);
    let x_public = PublicKey::from(&x_secret);

    let (kem_dk, kem_ek) = MlKem768::generate(&mut OsRng);
    let kem_secret = kem_dk.as_bytes().to_vec();
    let kem_public = kem_ek.as_bytes().to_vec();

    let fingerprint = fingerprint_of(x_public.as_bytes().as_slice(), &kem_public);

    Identity {
        x25519_secret: x_secret.to_bytes().to_vec(),
        x25519_public: x_public.as_bytes().to_vec(),
        mlkem_secret: kem_secret,
        mlkem_public: kem_public,
        fingerprint,
    }
}

/// Huella: SHA-256 sobre (x25519_pub ‖ mlkem_pub), primeros 10 bytes en grupos
/// hex de 4, p. ej. "3FA2 91C4 0B77 D2E0 5A19".
pub fn fingerprint_of(x25519_public: &[u8], mlkem_public: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(b"privmsg-v1-fingerprint");
    hasher.update(x25519_public);
    hasher.update(mlkem_public);
    let digest = hasher.finalize();

    digest[..10]
        .chunks(2)
        .map(|pair| format!("{:02X}{:02X}", pair[0], pair[1]))
        .collect::<Vec<_>>()
        .join(" ")
}

/// Valida longitudes y reconstruye un `Contact` desde claves públicas crudas.
pub fn contact_from_parts(x25519_public: &[u8], mlkem_public: &[u8]) -> Result<Contact, CoreError> {
    if x25519_public.len() != X25519_PUBLIC_LEN {
        return Err(CoreError::InvalidKey {
            reason: format!("clave X25519 de {} bytes (esperados 32)", x25519_public.len()),
        });
    }
    if mlkem_public.len() != MLKEM_PUBLIC_LEN {
        return Err(CoreError::InvalidKey {
            reason: format!(
                "clave ML-KEM de {} bytes (esperados {})",
                mlkem_public.len(),
                MLKEM_PUBLIC_LEN
            ),
        });
    }
    // Verifica que la clave ML-KEM decodifica correctamente.
    let arr: ml_kem::Encoded<<MlKem768 as KemCore>::EncapsulationKey> = mlkem_public
        .try_into()
        .map_err(|_| CoreError::InvalidKey {
            reason: "clave ML-KEM no decodificable".into(),
        })?;
    let _ek = <MlKem768 as KemCore>::EncapsulationKey::from_bytes(&arr);

    Ok(Contact {
        x25519_public: x25519_public.to_vec(),
        mlkem_public: mlkem_public.to_vec(),
        fingerprint: fingerprint_of(x25519_public, mlkem_public),
    })
}
