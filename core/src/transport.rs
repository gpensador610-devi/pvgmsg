//! Transporte mundial vía relays públicos (protocolo Nostr como buzón tonto).
//!
//! Privacidad del diseño:
//! - **Etiqueta de encuentro (rendezvous)**: derivada por HKDF del secreto
//!   X25519 estático-estático entre ambos contactos + el día actual. Solo las
//!   dos partes pueden calcularla; para un observador es un hex aleatorio que
//!   rota a diario. El relay no sabe quién habla con quién.
//! - **Remitente irrastreable**: cada evento se firma con una clave secp256k1
//!   aleatoria de un solo uso. Dos mensajes tuyos no son vinculables entre sí.
//! - **Contenido**: el blob sellado (híbrido post-cuántico) en base64. El relay
//!   solo almacena ruido.

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use hkdf::Hkdf;
use k256::schnorr::SigningKey;
use rand::rngs::OsRng;
use rand::RngCore;
use sha2::{Digest, Sha256};
use x25519_dalek::{PublicKey, StaticSecret};

use crate::error::CoreError;
use crate::identity::{Contact, Identity};

/// Kind Nostr "regular" (los relays lo almacenan y lo sirven a suscriptores).
const EVENT_KIND: u32 = 4004;

/// Etiqueta de encuentro del día `day` (días unix: `unix_secs / 86400`) entre
/// nuestra identidad y un contacto. Simétrica: ambos derivan la misma.
#[uniffi::export]
pub fn rendezvous_tag(identity: Identity, contact: Contact, day: u64) -> Result<String, CoreError> {
    let secret: [u8; 32] =
        identity
            .x25519_secret
            .as_slice()
            .try_into()
            .map_err(|_| CoreError::InvalidKey {
                reason: "secreto X25519 propio inválido".into(),
            })?;
    let their_pub: [u8; 32] =
        contact
            .x25519_public
            .as_slice()
            .try_into()
            .map_err(|_| CoreError::InvalidKey {
                reason: "clave X25519 del contacto inválida".into(),
            })?;

    let shared = StaticSecret::from(secret).diffie_hellman(&PublicKey::from(their_pub));
    let hk = Hkdf::<Sha256>::new(Some(b"privmsg-v1-rendezvous"), shared.as_bytes());
    let mut okm = [0u8; 16];
    hk.expand(&day.to_be_bytes(), &mut okm)
        .expect("16 bytes válidos para HKDF");
    Ok(hex::encode(okm))
}

/// Construye un evento Nostr firmado (JSON) que transporta `sealed` bajo la
/// etiqueta `tag`. Usa una clave de firma aleatoria de un solo uso.
#[uniffi::export]
pub fn make_transport_event(sealed: Vec<u8>, tag: String, created_at: u64) -> String {
    let signing_key = SigningKey::random(&mut OsRng);
    let pubkey_hex = hex::encode(signing_key.verifying_key().to_bytes());
    let content = B64.encode(&sealed);

    // id = sha256 de la serialización canónica [0, pubkey, created_at, kind, tags, content]
    let tags_json = serde_json::json!([["t", tag]]);
    let canonical = serde_json::json!([0, pubkey_hex, created_at, EVENT_KIND, tags_json, content]);
    let id_bytes = Sha256::digest(serde_json::to_string(&canonical).expect("json").as_bytes());

    let mut aux = [0u8; 32];
    OsRng.fill_bytes(&mut aux);
    let sig = signing_key
        .sign_raw(&id_bytes, &aux)
        .expect("firma BIP-340 sobre 32 bytes");

    serde_json::json!({
        "id": hex::encode(id_bytes),
        "pubkey": pubkey_hex,
        "created_at": created_at,
        "kind": EVENT_KIND,
        "tags": [["t", tag]],
        "content": content,
        "sig": hex::encode(sig.to_bytes()),
    })
    .to_string()
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
    fn rendezvous_is_symmetric_and_rotates() {
        let alice = generate_identity();
        let bob = generate_identity();

        let a_to_b = rendezvous_tag(alice.clone(), contact_of(&bob), 20_000).unwrap();
        let b_to_a = rendezvous_tag(bob.clone(), contact_of(&alice), 20_000).unwrap();
        assert_eq!(a_to_b, b_to_a, "ambos derivan la misma etiqueta");

        let next_day = rendezvous_tag(alice.clone(), contact_of(&bob), 20_001).unwrap();
        assert_ne!(a_to_b, next_day, "la etiqueta rota a diario");

        // Un tercero deriva otra etiqueta.
        let carol = generate_identity();
        let c = rendezvous_tag(carol, contact_of(&bob), 20_000).unwrap();
        assert_ne!(a_to_b, c);
    }

    #[test]
    fn transport_event_is_valid_json_with_expected_fields() {
        let ev = make_transport_event(b"blob sellado".to_vec(), "abc123".into(), 1_700_000_000);
        let parsed: serde_json::Value = serde_json::from_str(&ev).unwrap();
        assert_eq!(parsed["kind"], 4004);
        assert_eq!(parsed["tags"][0][0], "t");
        assert_eq!(parsed["tags"][0][1], "abc123");
        assert_eq!(parsed["id"].as_str().unwrap().len(), 64);
        assert_eq!(parsed["pubkey"].as_str().unwrap().len(), 64);
        assert_eq!(parsed["sig"].as_str().unwrap().len(), 128);

        // Claves de un solo uso: dos eventos no comparten remitente.
        let ev2 = make_transport_event(b"blob sellado".to_vec(), "abc123".into(), 1_700_000_000);
        let parsed2: serde_json::Value = serde_json::from_str(&ev2).unwrap();
        assert_ne!(parsed["pubkey"], parsed2["pubkey"]);
    }
}
