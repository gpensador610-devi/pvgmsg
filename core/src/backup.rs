//! Copia de seguridad cifrada con contraseña.
//!
//! La frase de 12 palabras salva *quién eres*; esto salva *lo que tienes*:
//! contactos, grupos, historial y ajustes.
//!
//! El archivo resultante es ruido sin la contraseña, así que puede guardarse
//! en cualquier sitio — incluida la nube — sin comprometer nada.
//!
//! ```text
//! [8: "PRIVBAK1"][16: salt][24: nonce][N: XChaCha20-Poly1305(datos)]
//! ```
//!
//! La clave se deriva con **Argon2id** (memoria 64 MiB, 3 pasadas): elegido
//! precisamente porque es caro en memoria, lo que arruina los ataques por
//! fuerza bruta con GPU o ASIC.

use argon2::{Algorithm, Argon2, Params, Version};
use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use rand::rngs::OsRng;
use rand::RngCore;
use zeroize::Zeroize;

use crate::error::CoreError;

const MAGIC: &[u8; 8] = b"PRIVBAK1";
const SALT_LEN: usize = 16;
const NONCE_LEN: usize = 24;
const HEADER_LEN: usize = 8 + SALT_LEN + NONCE_LEN;

/// Parámetros Argon2id: 64 MiB, 3 pasadas, 4 hilos.
const ARGON_MEM_KIB: u32 = 65_536;
const ARGON_PASSES: u32 = 3;
const ARGON_LANES: u32 = 4;

fn derive_key(password: &str, salt: &[u8]) -> Result<[u8; 32], CoreError> {
    let params = Params::new(ARGON_MEM_KIB, ARGON_PASSES, ARGON_LANES, Some(32)).map_err(|e| {
        CoreError::BackupFailed {
            reason: format!("parámetros Argon2 inválidos: {e}"),
        }
    })?;
    let argon = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);

    let mut key = [0u8; 32];
    argon
        .hash_password_into(password.as_bytes(), salt, &mut key)
        .map_err(|e| CoreError::BackupFailed {
            reason: format!("derivación de clave falló: {e}"),
        })?;
    Ok(key)
}

/// Cifra los datos de la copia de seguridad con una contraseña.
#[uniffi::export]
pub fn backup_encrypt(plaintext: Vec<u8>, password: String) -> Result<Vec<u8>, CoreError> {
    if password.chars().count() < 8 {
        return Err(CoreError::BackupFailed {
            reason: "la contraseña debe tener al menos 8 caracteres".into(),
        });
    }

    let mut salt = [0u8; SALT_LEN];
    let mut nonce = [0u8; NONCE_LEN];
    OsRng.fill_bytes(&mut salt);
    OsRng.fill_bytes(&mut nonce);

    let mut key = derive_key(&password, &salt)?;
    let cipher = XChaCha20Poly1305::new((&key).into());
    let ciphertext = cipher
        .encrypt(XNonce::from_slice(&nonce), plaintext.as_slice())
        .map_err(|_| CoreError::BackupFailed {
            reason: "fallo al cifrar la copia".into(),
        })?;
    key.zeroize();

    let mut out = Vec::with_capacity(HEADER_LEN + ciphertext.len());
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&salt);
    out.extend_from_slice(&nonce);
    out.extend_from_slice(&ciphertext);
    Ok(out)
}

/// Descifra una copia de seguridad. Falla si la contraseña es incorrecta.
#[uniffi::export]
pub fn backup_decrypt(archive: Vec<u8>, password: String) -> Result<Vec<u8>, CoreError> {
    if archive.len() < HEADER_LEN + 16 {
        return Err(CoreError::BackupFailed {
            reason: "archivo demasiado corto o corrupto".into(),
        });
    }
    if &archive[..8] != MAGIC {
        return Err(CoreError::BackupFailed {
            reason: "no es una copia de seguridad de PrivMsg".into(),
        });
    }

    let salt = &archive[8..8 + SALT_LEN];
    let nonce = &archive[8 + SALT_LEN..HEADER_LEN];
    let ciphertext = &archive[HEADER_LEN..];

    let mut key = derive_key(&password, salt)?;
    let cipher = XChaCha20Poly1305::new((&key).into());
    let plaintext = cipher
        .decrypt(XNonce::from_slice(nonce), ciphertext)
        .map_err(|_| CoreError::BackupFailed {
            reason: "contraseña incorrecta o archivo dañado".into(),
        });
    key.zeroize();
    plaintext
}

/// Parámetros del PIN: más ligeros que la copia de seguridad porque esto se
/// verifica cada vez que abres la app, pero aun así costosos en memoria para
/// que probar los 10.000 PIN de 4 dígitos no sea instantáneo.
const PIN_MEM_KIB: u32 = 32_768;
const PIN_PASSES: u32 = 2;

/// Deriva el verificador del PIN de bloqueo de la app.
///
/// No se guarda el PIN: se guarda esto, del que es inviable volver atrás.
#[uniffi::export]
pub fn pin_hash(pin: String, salt: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    if salt.len() < 8 {
        return Err(CoreError::BackupFailed {
            reason: "salt demasiado corto".into(),
        });
    }
    let params = Params::new(PIN_MEM_KIB, PIN_PASSES, ARGON_LANES, Some(32)).map_err(|e| {
        CoreError::BackupFailed {
            reason: format!("parámetros Argon2 inválidos: {e}"),
        }
    })?;
    let argon = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);

    let mut out = [0u8; 32];
    argon
        .hash_password_into(pin.as_bytes(), &salt, &mut out)
        .map_err(|e| CoreError::BackupFailed {
            reason: format!("derivación del PIN falló: {e}"),
        })?;
    Ok(out.to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pin_hash_es_determinista_y_sensible() {
        let salt = vec![1u8; 16];
        let a = pin_hash("1234".into(), salt.clone()).unwrap();
        let b = pin_hash("1234".into(), salt.clone()).unwrap();
        assert_eq!(a, b, "el mismo PIN y salt dan el mismo verificador");

        // Un dígito distinto cambia todo.
        assert_ne!(a, pin_hash("1235".into(), salt).unwrap());
        // El mismo PIN con otro salt también.
        assert_ne!(a, pin_hash("1234".into(), vec![2u8; 16]).unwrap());
        // Y nunca contiene el PIN en claro.
        assert!(!a.windows(4).any(|w| w == b"1234"));
    }

    #[test]
    fn backup_roundtrip() {
        let data = b"contactos, grupos e historial".to_vec();
        let archive = backup_encrypt(data.clone(), "contrasena-larga".into()).unwrap();

        assert_eq!(&archive[..8], MAGIC);
        assert!(!archive.windows(data.len()).any(|w| w == data), "no debe verse el texto");

        let restored = backup_decrypt(archive, "contrasena-larga".into()).unwrap();
        assert_eq!(restored, data);
    }

    #[test]
    fn wrong_password_fails() {
        let archive = backup_encrypt(b"secreto".to_vec(), "contrasena-buena".into()).unwrap();
        assert!(backup_decrypt(archive, "contrasena-mala".into()).is_err());
    }

    #[test]
    fn rejects_short_password_and_foreign_files() {
        assert!(backup_encrypt(b"x".to_vec(), "corta".into()).is_err());
        assert!(backup_decrypt(vec![0u8; 100], "contrasena-larga".into()).is_err());
    }

    #[test]
    fn each_backup_differs() {
        let a = backup_encrypt(b"mismo".to_vec(), "contrasena-larga".into()).unwrap();
        let b = backup_encrypt(b"mismo".to_vec(), "contrasena-larga".into()).unwrap();
        assert_ne!(a, b, "salt y nonce aleatorios por copia");
    }

    #[test]
    fn tampered_archive_fails() {
        let mut archive = backup_encrypt(b"integridad".to_vec(), "contrasena-larga".into()).unwrap();
        let last = archive.len() - 1;
        archive[last] ^= 0x01;
        assert!(backup_decrypt(archive, "contrasena-larga".into()).is_err());
    }
}
