//! privmsg-core: núcleo criptográfico de la app de mensajería privada.
//!
//! Diseño (v1):
//! - Identidad = par híbrido X25519 (clásico) + ML-KEM-768 (post-cuántico, FIPS 203).
//! - Invitación = clave pública híbrida serializada, apta para QR (base64url).
//! - Sellado de mensajes = KEM híbrido (ECDH efímero + encapsulación ML-KEM),
//!   combinado con HKDF-SHA256, cifrado XChaCha20-Poly1305.
//!
//! Romper un mensaje exige romper *ambas* matemáticas (curvas elípticas Y retículos).
//! El ratchet (forward secrecy por mensaje) se añade en una fase posterior sobre
//! esta misma base.

mod backup;
mod call;
mod crypto;
mod error;
mod identity;
mod invite;
mod ratchet;
mod recovery;
mod transport;

pub use backup::{backup_decrypt, backup_encrypt, pin_hash};
pub use call::{
    call_counter_acceptable, call_derive_keys, call_new_secret, call_open_packet, call_seal_packet,
    CallKeys, VoicePacket,
};
pub use error::CoreError;
pub use identity::{fingerprint_of, generate_identity, Contact, Identity};
pub use invite::{invite_decode, invite_encode};
pub use ratchet::{
    ratchet_decrypt, ratchet_encrypt, ratchet_init_initiator, ratchet_init_responder,
    RatchetResult,
};
pub use recovery::{generate_mnemonic, identity_from_mnemonic, validate_mnemonic};
pub use transport::{make_transport_event, rendezvous_tag};

uniffi::setup_scaffolding!();
