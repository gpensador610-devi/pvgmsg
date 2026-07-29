use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;

use crate::error::CoreError;
use crate::identity::{contact_from_parts, Contact, Identity, MLKEM_PUBLIC_LEN, X25519_PUBLIC_LEN};

/// Versión del formato de invitación.
const INVITE_VERSION: u8 = 1;
/// Prefijo del enlace de invitación (también funciona como texto plano en QR).
const INVITE_PREFIX: &str = "privmsg:";

/// Codifica la parte pública de la identidad como invitación apta para QR.
///
/// Formato: `privmsg:` + base64url( [1: versión][32: x25519_pub][1184: mlkem_pub] )
#[uniffi::export]
pub fn invite_encode(identity: Identity) -> String {
    let mut payload = Vec::with_capacity(1 + X25519_PUBLIC_LEN + MLKEM_PUBLIC_LEN);
    payload.push(INVITE_VERSION);
    payload.extend_from_slice(&identity.x25519_public);
    payload.extend_from_slice(&identity.mlkem_public);
    format!("{INVITE_PREFIX}{}", URL_SAFE_NO_PAD.encode(payload))
}

/// Decodifica y valida una invitación escaneada; devuelve el contacto público.
#[uniffi::export]
pub fn invite_decode(invite: String) -> Result<Contact, CoreError> {
    let trimmed = invite.trim();
    let encoded = trimmed
        .strip_prefix(INVITE_PREFIX)
        .ok_or_else(|| CoreError::InvalidInvite {
            reason: "no empieza con 'privmsg:'".into(),
        })?;

    let payload = URL_SAFE_NO_PAD
        .decode(encoded)
        .map_err(|e| CoreError::InvalidInvite {
            reason: format!("base64 inválido: {e}"),
        })?;

    let expected = 1 + X25519_PUBLIC_LEN + MLKEM_PUBLIC_LEN;
    if payload.len() != expected {
        return Err(CoreError::InvalidInvite {
            reason: format!("longitud {} (esperada {expected})", payload.len()),
        });
    }
    if payload[0] != INVITE_VERSION {
        return Err(CoreError::InvalidInvite {
            reason: format!("versión {} no soportada", payload[0]),
        });
    }

    let x_pub = &payload[1..1 + X25519_PUBLIC_LEN];
    let kem_pub = &payload[1 + X25519_PUBLIC_LEN..];
    contact_from_parts(x_pub, kem_pub)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::generate_identity;

    #[test]
    fn invite_roundtrip() {
        let id = generate_identity();
        let invite = invite_encode(id.clone());
        assert!(invite.starts_with("privmsg:"));

        let contact = invite_decode(invite).expect("decodifica");
        assert_eq!(contact.x25519_public, id.x25519_public);
        assert_eq!(contact.mlkem_public, id.mlkem_public);
        assert_eq!(contact.fingerprint, id.fingerprint);
    }

    #[test]
    fn invite_rejects_garbage() {
        assert!(invite_decode("hola".into()).is_err());
        assert!(invite_decode("privmsg:###".into()).is_err());
        assert!(invite_decode("privmsg:AAAA".into()).is_err());
    }
}
