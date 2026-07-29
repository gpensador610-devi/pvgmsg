//! Criptografía de las llamadas de voz.
//!
//! Una llamada no puede hacer un KEM híbrido por paquete (hay 50 paquetes por
//! segundo). El diseño correcto, igual que SRTP:
//!
//! 1. **Una sola vez, al establecer la llamada**: quien llama genera un secreto
//!    de sesión aleatorio de 32 bytes y se lo manda al otro *usando el sobre
//!    cifrado normal* — es decir, con el KEM híbrido post-cuántico completo.
//! 2. De ese secreto se derivan **dos claves distintas**, una por sentido. Que
//!    sean direccionales evita ataques de reflexión (reenviarte tus propios
//!    paquetes).
//! 3. Cada paquete de audio se cifra con XChaCha20-Poly1305 usando un **nonce
//!    con contador**, nunca repetido, y se rechazan los repetidos (anti-replay).
//!
//! Resultado: la seguridad post-cuántica del establecimiento protege toda la
//! llamada, y cifrar cada paquete cuesta microsegundos.

use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use rand::rngs::OsRng;
use rand::RngCore;
use sha2::Sha256;

use crate::error::CoreError;

/// Bytes de contador que preceden a cada paquete cifrado.
const COUNTER_LEN: usize = 8;
/// Ventana de anti-replay (paquetes que pueden llegar desordenados).
const REPLAY_WINDOW: u64 = 128;

/// Par de claves de una llamada: una para enviar, otra para recibir.
#[derive(uniffi::Record, Clone)]
pub struct CallKeys {
    pub send_key: Vec<u8>,
    pub recv_key: Vec<u8>,
}

/// Genera el secreto de sesión que quien llama envía en la invitación.
/// Viaja dentro del sobre cifrado normal (híbrido post-cuántico).
#[uniffi::export]
pub fn call_new_secret() -> Vec<u8> {
    let mut secret = vec![0u8; 32];
    OsRng.fill_bytes(&mut secret);
    secret
}

/// Deriva las claves direccionales del secreto de sesión.
///
/// `is_caller` decide qué clave es de envío y cuál de recepción, para que
/// ambos extremos acaben con el par cruzado correctamente.
#[uniffi::export]
pub fn call_derive_keys(secret: Vec<u8>, is_caller: bool) -> Result<CallKeys, CoreError> {
    if secret.len() != 32 {
        return Err(CoreError::InvalidKey {
            reason: format!("secreto de llamada de {} bytes (esperados 32)", secret.len()),
        });
    }
    let hk = Hkdf::<Sha256>::new(Some(b"privmsg-v1-call"), &secret);

    let mut caller_to_callee = [0u8; 32];
    let mut callee_to_caller = [0u8; 32];
    hk.expand(b"caller->callee", &mut caller_to_callee)
        .expect("32 bytes válidos");
    hk.expand(b"callee->caller", &mut callee_to_caller)
        .expect("32 bytes válidos");

    Ok(if is_caller {
        CallKeys {
            send_key: caller_to_callee.to_vec(),
            recv_key: callee_to_caller.to_vec(),
        }
    } else {
        CallKeys {
            send_key: callee_to_caller.to_vec(),
            recv_key: caller_to_callee.to_vec(),
        }
    })
}

/// Cifra un paquete de audio. Formato: `[8: contador BE][ciphertext+tag]`.
#[uniffi::export]
pub fn call_seal_packet(key: Vec<u8>, counter: u64, audio: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    let cipher = cipher_from(&key)?;
    let nonce = nonce_for(counter);

    let ciphertext = cipher
        .encrypt(XNonce::from_slice(&nonce), audio.as_slice())
        .map_err(|_| CoreError::MalformedMessage {
            reason: "fallo al cifrar paquete de voz".into(),
        })?;

    let mut out = Vec::with_capacity(COUNTER_LEN + ciphertext.len());
    out.extend_from_slice(&counter.to_be_bytes());
    out.extend_from_slice(&ciphertext);
    Ok(out)
}

/// Paquete de voz descifrado, con su número de secuencia.
#[derive(uniffi::Record)]
pub struct VoicePacket {
    pub counter: u64,
    pub audio: Vec<u8>,
}

/// Descifra un paquete de audio y devuelve su contador para el anti-replay.
#[uniffi::export]
pub fn call_open_packet(key: Vec<u8>, packet: Vec<u8>) -> Result<VoicePacket, CoreError> {
    if packet.len() <= COUNTER_LEN {
        return Err(CoreError::MalformedMessage {
            reason: "paquete de voz demasiado corto".into(),
        });
    }
    let counter = u64::from_be_bytes(packet[..COUNTER_LEN].try_into().unwrap());
    let cipher = cipher_from(&key)?;
    let nonce = nonce_for(counter);

    let audio = cipher
        .decrypt(XNonce::from_slice(&nonce), &packet[COUNTER_LEN..])
        .map_err(|_| CoreError::DecryptionFailed)?;

    Ok(VoicePacket { counter, audio })
}

/// ¿Es aceptable este contador, o es un repetido/demasiado viejo?
/// El llamador mantiene `highest_seen` y lo actualiza si esto devuelve true.
#[uniffi::export]
pub fn call_counter_acceptable(counter: u64, highest_seen: u64) -> bool {
    counter > highest_seen || (highest_seen - counter) < REPLAY_WINDOW
}

fn cipher_from(key: &[u8]) -> Result<XChaCha20Poly1305, CoreError> {
    let bytes: [u8; 32] = key.try_into().map_err(|_| CoreError::InvalidKey {
        reason: "clave de llamada inválida".into(),
    })?;
    Ok(XChaCha20Poly1305::new((&bytes).into()))
}

/// Nonce de 24 bytes: 16 de relleno fijo + 8 del contador. Único por clave,
/// porque cada clave es de un solo sentido de una sola llamada.
fn nonce_for(counter: u64) -> [u8; 24] {
    let mut nonce = [0u8; 24];
    nonce[..8].copy_from_slice(b"privcall");
    nonce[16..].copy_from_slice(&counter.to_be_bytes());
    nonce
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn directional_keys_cross_correctly() {
        let secret = call_new_secret();
        let caller = call_derive_keys(secret.clone(), true).unwrap();
        let callee = call_derive_keys(secret, false).unwrap();

        assert_eq!(caller.send_key, callee.recv_key);
        assert_eq!(caller.recv_key, callee.send_key);
        assert_ne!(caller.send_key, caller.recv_key, "sentidos con claves distintas");
    }

    #[test]
    fn packet_roundtrip_both_directions() {
        let secret = call_new_secret();
        let caller = call_derive_keys(secret.clone(), true).unwrap();
        let callee = call_derive_keys(secret, false).unwrap();

        let audio = vec![7u8; 160];
        let sealed = call_seal_packet(caller.send_key.clone(), 42, audio.clone()).unwrap();
        let opened = call_open_packet(callee.recv_key.clone(), sealed).unwrap();
        assert_eq!(opened.audio, audio);
        assert_eq!(opened.counter, 42);

        // Y de vuelta.
        let reply = call_seal_packet(callee.send_key, 1, b"pong".to_vec()).unwrap();
        assert_eq!(call_open_packet(caller.recv_key, reply).unwrap().audio, b"pong");
    }

    #[test]
    fn reflected_packet_is_rejected() {
        let secret = call_new_secret();
        let caller = call_derive_keys(secret, true).unwrap();

        // Un atacante reenvía al emisor su propio paquete: no debe abrirse,
        // porque su clave de recepción es distinta de la de envío.
        let sealed = call_seal_packet(caller.send_key, 1, b"hola".to_vec()).unwrap();
        assert!(call_open_packet(caller.recv_key, sealed).is_err());
    }

    #[test]
    fn tampered_packet_is_rejected() {
        let secret = call_new_secret();
        let keys = call_derive_keys(secret, true).unwrap();
        let mut sealed = call_seal_packet(keys.send_key.clone(), 5, b"audio".to_vec()).unwrap();
        let last = sealed.len() - 1;
        sealed[last] ^= 0xFF;
        assert!(call_open_packet(keys.send_key, sealed).is_err());
    }

    #[test]
    fn replay_window_logic() {
        assert!(call_counter_acceptable(100, 99), "nuevo");
        assert!(call_counter_acceptable(50, 100), "desordenado pero dentro de ventana");
        assert!(!call_counter_acceptable(1, 1000), "demasiado viejo: replay");
    }
}
