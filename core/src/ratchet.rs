//! Double Ratchet: una clave nueva por mensaje, y la anterior se destruye.
//!
//! # Qué resuelve
//!
//! Sin esto, la clave estática del dispositivo abre **todo** el historial: si
//! te la roban hoy, leen lo de hace tres años. El ratchet corta eso.
//!
//! - **Forward secrecy**: cada mensaje usa su propia clave y se borra al usarla.
//!   Robar el teléfono hoy no descifra lo de ayer.
//! - **Post-compromise security**: cada vez que los dos extremos se responden,
//!   se inyecta aleatoriedad nueva y la conversación **se cura sola**; quien
//!   robó una clave vuelve a quedarse fuera sin que nadie haga nada.
//!
//! # Los dos engranajes
//!
//! ```text
//! cadena simétrica   RK ──DH nueva──> RK' ──DH nueva──> RK''   (ratchet DH)
//!       │                  │                  │
//!       ├─ CK ─> MK1       ├─ CK' ─> MK1'     └─ ...
//!       ├─ CK ─> MK2       └─ ...
//!       └─ ...
//! ```
//!
//! El engranaje simétrico avanza con cada mensaje (barato). El engranaje DH
//! avanza cuando llega un mensaje con una clave de ratchet nueva, y es el que
//! cura la sesión.
//!
//! # Seguridad post-cuántica
//!
//! El ratchet DH usa X25519, que un ordenador cuántico rompería. No importa:
//! la **raíz** de la cadena (`root_key`) nace del intercambio híbrido
//! X25519 + ML-KEM-768 del establecimiento. Cada paso mezcla la raíz anterior
//! con el DH nuevo, así que romper X25519 sin tener el secreto ML-KEM original
//! no permite reconstruir ninguna raíz. Es el mismo razonamiento de PQXDH.
//!
//! # Mensajes desordenados
//!
//! Los relays no garantizan orden. Si llega el mensaje 5 antes que el 3, se
//! guardan las claves de los saltados (`skipped`) para poder abrirlos cuando
//! aparezcan. Está acotado a `MAX_SKIP` por cadena y `MAX_SKIPPED_TOTAL` en
//! total: sin ese límite, un atacante podría reventar la memoria mandando un
//! mensaje con un número altísimo.

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use rand::rngs::OsRng;
use sha2::Sha256;
use std::collections::HashMap;
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroize;

use crate::error::CoreError;

type HmacSha256 = Hmac<Sha256>;

/// Máximo de mensajes que se pueden saltar dentro de una misma cadena.
const MAX_SKIP: u32 = 1_000;
/// Máximo de claves saltadas guardadas en total (acota la memoria).
const MAX_SKIPPED_TOTAL: usize = 2_000;

const HEADER_LEN: usize = 32 + 4 + 4;

/// Estado de una conversación. Se persiste cifrado en el dispositivo.
struct Session {
    root_key: [u8; 32],

    /// Nuestro par de ratchet actual.
    dhs_secret: [u8; 32],
    dhs_public: [u8; 32],

    /// Clave de ratchet del otro extremo, si ya la conocemos.
    dhr_public: Option<[u8; 32]>,

    /// Cadenas de envío y recepción.
    cks: Option<[u8; 32]>,
    ckr: Option<[u8; 32]>,

    /// Contadores: enviados, recibidos y longitud de la cadena anterior.
    ns: u32,
    nr: u32,
    pn: u32,

    /// Claves de mensajes que aún no llegaron: (ratchet_pub, n) -> message_key.
    skipped: HashMap<([u8; 32], u32), [u8; 32]>,
}

/// Resultado de una operación: la sesión actualizada y los datos producidos.
#[derive(uniffi::Record)]
pub struct RatchetResult {
    /// Estado nuevo de la sesión. **Hay que persistirlo siempre.**
    pub session: Vec<u8>,
    pub data: Vec<u8>,
}

// ---------- derivación de claves ----------

/// Avanza la raíz con un secreto Diffie-Hellman nuevo. Devuelve (raíz, cadena).
fn kdf_root(root_key: &[u8; 32], dh_out: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    let hk = Hkdf::<Sha256>::new(Some(root_key), dh_out);
    let mut okm = [0u8; 64];
    hk.expand(b"privmsg-ratchet-root", &mut okm)
        .expect("64 bytes válidos");
    let mut new_root = [0u8; 32];
    let mut chain = [0u8; 32];
    new_root.copy_from_slice(&okm[..32]);
    chain.copy_from_slice(&okm[32..]);
    okm.zeroize();
    (new_root, chain)
}

/// Avanza la cadena un mensaje. Devuelve (cadena nueva, clave del mensaje).
///
/// Solo se puede ir hacia adelante: de la cadena nueva es imposible volver a
/// la anterior. Ahí está el trinquete.
fn kdf_chain(chain_key: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    // Cualificado: `KeyInit` del AEAD también expone `new_from_slice`.
    let mut mac = <HmacSha256 as Mac>::new_from_slice(chain_key).expect("HMAC acepta 32 bytes");
    mac.update(&[0x01]);
    let message_key: [u8; 32] = mac.finalize().into_bytes().into();

    // Cualificado: `KeyInit` del AEAD también expone `new_from_slice`.
    let mut mac = <HmacSha256 as Mac>::new_from_slice(chain_key).expect("HMAC acepta 32 bytes");
    mac.update(&[0x02]);
    let next_chain: [u8; 32] = mac.finalize().into_bytes().into();

    (next_chain, message_key)
}

/// De la clave de mensaje salen la clave AEAD y su nonce. Cada clave se usa
/// una sola vez, así que el nonce nunca se repite.
fn message_cipher(message_key: &[u8; 32]) -> (XChaCha20Poly1305, [u8; 24]) {
    let hk = Hkdf::<Sha256>::new(None, message_key);
    let mut okm = [0u8; 56];
    hk.expand(b"privmsg-ratchet-msg", &mut okm)
        .expect("56 bytes válidos");
    let mut key = [0u8; 32];
    let mut nonce = [0u8; 24];
    key.copy_from_slice(&okm[..32]);
    nonce.copy_from_slice(&okm[32..]);
    okm.zeroize();
    let cipher = XChaCha20Poly1305::new((&key).into());
    key.zeroize();
    (cipher, nonce)
}

fn dh(secret: &[u8; 32], public: &[u8; 32]) -> [u8; 32] {
    StaticSecret::from(*secret)
        .diffie_hellman(&PublicKey::from(*public))
        .to_bytes()
}

// ---------- API pública ----------

/// Inicia la sesión en el lado de quien empieza la conversación.
///
/// `seed` es el secreto de 32 bytes que se envió al otro extremo **dentro del
/// sobre cifrado híbrido**; de ahí viene la protección post-cuántica.
///
/// Devuelve la sesión y la clave pública de ratchet que hay que enviarle.
#[uniffi::export]
pub fn ratchet_init_initiator(seed: Vec<u8>) -> Result<RatchetResult, CoreError> {
    let seed: [u8; 32] = seed.as_slice().try_into().map_err(|_| CoreError::InvalidKey {
        reason: "la semilla de sesión debe tener 32 bytes".into(),
    })?;

    let (root_key, chain) = derive_initial(&seed);
    let dhs_secret = StaticSecret::random_from_rng(OsRng);
    let dhs_public = PublicKey::from(&dhs_secret);

    let session = Session {
        root_key,
        dhs_secret: dhs_secret.to_bytes(),
        dhs_public: *dhs_public.as_bytes(),
        dhr_public: None,
        // Quien inicia envía con la cadena que sale de la semilla; el ratchet
        // DH arranca cuando el otro responde con su clave.
        cks: Some(chain),
        ckr: None,
        ns: 0,
        nr: 0,
        pn: 0,
        skipped: HashMap::new(),
    };

    Ok(RatchetResult {
        session: serialize(&session),
        data: session.dhs_public.to_vec(),
    })
}

/// Inicia la sesión en el lado de quien recibe la invitación.
#[uniffi::export]
pub fn ratchet_init_responder(
    seed: Vec<u8>,
    initiator_ratchet_public: Vec<u8>,
) -> Result<Vec<u8>, CoreError> {
    let seed: [u8; 32] = seed.as_slice().try_into().map_err(|_| CoreError::InvalidKey {
        reason: "la semilla de sesión debe tener 32 bytes".into(),
    })?;
    let their_pub: [u8; 32] = initiator_ratchet_public
        .as_slice()
        .try_into()
        .map_err(|_| CoreError::InvalidKey {
            reason: "clave de ratchet inválida".into(),
        })?;

    let (root_key, chain) = derive_initial(&seed);
    let dhs_secret = StaticSecret::random_from_rng(OsRng);
    let dhs_public = PublicKey::from(&dhs_secret);

    let session = Session {
        root_key,
        dhs_secret: dhs_secret.to_bytes(),
        dhs_public: *dhs_public.as_bytes(),
        dhr_public: Some(their_pub),
        cks: None,
        ckr: Some(chain),
        ns: 0,
        nr: 0,
        pn: 0,
        skipped: HashMap::new(),
    };

    Ok(serialize(&session))
}

fn derive_initial(seed: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    let hk = Hkdf::<Sha256>::new(Some(b"privmsg-ratchet-init"), seed);
    let mut okm = [0u8; 64];
    hk.expand(b"initial", &mut okm).expect("64 bytes válidos");
    let mut root = [0u8; 32];
    let mut chain = [0u8; 32];
    root.copy_from_slice(&okm[..32]);
    chain.copy_from_slice(&okm[32..]);
    okm.zeroize();
    (root, chain)
}

/// Cifra un mensaje y avanza el trinquete.
#[uniffi::export]
pub fn ratchet_encrypt(session: Vec<u8>, plaintext: Vec<u8>) -> Result<RatchetResult, CoreError> {
    let mut s = deserialize(&session)?;

    // Si aún no tenemos cadena de envío, hay que dar un paso de ratchet DH.
    if s.cks.is_none() {
        let their_pub = s.dhr_public.ok_or(CoreError::RatchetFailed {
            reason: "no se puede enviar todavía: falta la clave del otro extremo".into(),
        })?;
        let secret = StaticSecret::random_from_rng(OsRng);
        s.dhs_secret = secret.to_bytes();
        s.dhs_public = *PublicKey::from(&secret).as_bytes();
        let (root, chain) = kdf_root(&s.root_key, &dh(&s.dhs_secret, &their_pub));
        s.root_key = root;
        s.cks = Some(chain);
        s.pn = s.ns;
        s.ns = 0;
    }

    let chain = s.cks.expect("acabamos de asegurarnos de que existe");
    let (next_chain, message_key) = kdf_chain(&chain);
    s.cks = Some(next_chain);

    let header = build_header(&s.dhs_public, s.pn, s.ns);
    s.ns += 1;

    let (cipher, nonce) = message_cipher(&message_key);
    let ciphertext = cipher
        .encrypt(
            XNonce::from_slice(&nonce),
            Payload {
                msg: &plaintext,
                aad: &header,
            },
        )
        .map_err(|_| CoreError::RatchetFailed {
            reason: "fallo al cifrar".into(),
        })?;

    let mut out = Vec::with_capacity(HEADER_LEN + ciphertext.len());
    out.extend_from_slice(&header);
    out.extend_from_slice(&ciphertext);

    Ok(RatchetResult {
        session: serialize(&s),
        data: out,
    })
}

/// Descifra un mensaje, avanzando el trinquete y saltando huecos si hace falta.
#[uniffi::export]
pub fn ratchet_decrypt(session: Vec<u8>, message: Vec<u8>) -> Result<RatchetResult, CoreError> {
    if message.len() < HEADER_LEN {
        return Err(CoreError::RatchetFailed {
            reason: "mensaje demasiado corto".into(),
        });
    }
    let mut s = deserialize(&session)?;

    let header = &message[..HEADER_LEN];
    let their_pub: [u8; 32] = header[..32].try_into().unwrap();
    let pn = u32::from_be_bytes(header[32..36].try_into().unwrap());
    let n = u32::from_be_bytes(header[36..40].try_into().unwrap());
    let ciphertext = &message[HEADER_LEN..];

    // 1. ¿Es un mensaje viejo cuya clave habíamos guardado?
    if let Some(message_key) = s.skipped.remove(&(their_pub, n)) {
        let plaintext = open(&message_key, header, ciphertext)?;
        return Ok(RatchetResult {
            session: serialize(&s),
            data: plaintext,
        });
    }

    // 2. ¿Trae una clave de ratchet nueva? Entonces toca paso DH.
    if s.dhr_public != Some(their_pub) {
        skip_message_keys(&mut s, pn)?;
        dh_ratchet(&mut s, their_pub);
    }

    // 3. Saltar los que falten dentro de esta cadena.
    skip_message_keys(&mut s, n)?;

    let chain = s.ckr.ok_or(CoreError::RatchetFailed {
        reason: "sin cadena de recepción".into(),
    })?;
    let (next_chain, message_key) = kdf_chain(&chain);
    s.ckr = Some(next_chain);
    s.nr += 1;

    let plaintext = open(&message_key, header, ciphertext)?;
    Ok(RatchetResult {
        session: serialize(&s),
        data: plaintext,
    })
}

fn open(message_key: &[u8; 32], header: &[u8], ciphertext: &[u8]) -> Result<Vec<u8>, CoreError> {
    let (cipher, nonce) = message_cipher(message_key);
    cipher
        .decrypt(
            XNonce::from_slice(&nonce),
            Payload {
                msg: ciphertext,
                aad: header,
            },
        )
        .map_err(|_| CoreError::DecryptionFailed)
}

/// Guarda las claves de los mensajes que aún no han llegado, hasta `until`.
fn skip_message_keys(s: &mut Session, until: u32) -> Result<(), CoreError> {
    let chain = match s.ckr {
        Some(chain) => chain,
        None => return Ok(()),
    };
    if until < s.nr {
        return Ok(());
    }
    if until - s.nr > MAX_SKIP {
        return Err(CoreError::RatchetFailed {
            reason: format!("salto de {} mensajes: demasiado grande", until - s.nr),
        });
    }

    let their_pub = s.dhr_public.unwrap_or([0u8; 32]);
    let mut current = chain;
    while s.nr < until {
        let (next, message_key) = kdf_chain(&current);
        current = next;
        s.skipped.insert((their_pub, s.nr), message_key);
        s.nr += 1;
    }
    s.ckr = Some(current);

    // Poda: si se acumulan demasiadas, se tiran las más antiguas.
    if s.skipped.len() > MAX_SKIPPED_TOTAL {
        let mut keys: Vec<_> = s.skipped.keys().copied().collect();
        keys.sort_by_key(|(_, n)| *n);
        for key in keys.into_iter().take(s.skipped.len() - MAX_SKIPPED_TOTAL) {
            s.skipped.remove(&key);
        }
    }
    Ok(())
}

/// Paso de ratchet DH: entra aleatoriedad nueva y la sesión se cura.
fn dh_ratchet(s: &mut Session, their_pub: [u8; 32]) {
    s.pn = s.ns;
    s.ns = 0;
    s.nr = 0;
    s.dhr_public = Some(their_pub);

    let (root, chain) = kdf_root(&s.root_key, &dh(&s.dhs_secret, &their_pub));
    s.root_key = root;
    s.ckr = Some(chain);

    // Nuestro par nuevo, que viajará en la cabecera del próximo envío.
    let secret = StaticSecret::random_from_rng(OsRng);
    s.dhs_secret = secret.to_bytes();
    s.dhs_public = *PublicKey::from(&secret).as_bytes();

    let (root, chain) = kdf_root(&s.root_key, &dh(&s.dhs_secret, &their_pub));
    s.root_key = root;
    s.cks = Some(chain);
}

fn build_header(ratchet_public: &[u8; 32], pn: u32, n: u32) -> Vec<u8> {
    let mut header = Vec::with_capacity(HEADER_LEN);
    header.extend_from_slice(ratchet_public);
    header.extend_from_slice(&pn.to_be_bytes());
    header.extend_from_slice(&n.to_be_bytes());
    header
}

// ---------- serialización del estado ----------

fn serialize(s: &Session) -> Vec<u8> {
    let mut out = Vec::new();
    out.push(1u8); // versión del formato
    out.extend_from_slice(&s.root_key);
    out.extend_from_slice(&s.dhs_secret);
    out.extend_from_slice(&s.dhs_public);

    out.push(s.dhr_public.is_some() as u8);
    out.extend_from_slice(&s.dhr_public.unwrap_or([0u8; 32]));
    out.push(s.cks.is_some() as u8);
    out.extend_from_slice(&s.cks.unwrap_or([0u8; 32]));
    out.push(s.ckr.is_some() as u8);
    out.extend_from_slice(&s.ckr.unwrap_or([0u8; 32]));

    out.extend_from_slice(&s.ns.to_be_bytes());
    out.extend_from_slice(&s.nr.to_be_bytes());
    out.extend_from_slice(&s.pn.to_be_bytes());

    out.extend_from_slice(&(s.skipped.len() as u32).to_be_bytes());
    for ((pubkey, n), message_key) in &s.skipped {
        out.extend_from_slice(pubkey);
        out.extend_from_slice(&n.to_be_bytes());
        out.extend_from_slice(message_key);
    }
    out
}

fn deserialize(bytes: &[u8]) -> Result<Session, CoreError> {
    let bad = |reason: &str| CoreError::RatchetFailed {
        reason: reason.into(),
    };
    if bytes.len() < 1 + 32 * 3 + 3 * 33 + 12 + 4 || bytes[0] != 1 {
        return Err(bad("estado de sesión corrupto"));
    }
    let mut pos = 1;
    let mut take32 = |pos: &mut usize| -> [u8; 32] {
        let value: [u8; 32] = bytes[*pos..*pos + 32].try_into().unwrap();
        *pos += 32;
        value
    };

    let root_key = take32(&mut pos);
    let dhs_secret = take32(&mut pos);
    let dhs_public = take32(&mut pos);

    let mut take_opt = |pos: &mut usize| -> Option<[u8; 32]> {
        let present = bytes[*pos] == 1;
        *pos += 1;
        let value: [u8; 32] = bytes[*pos..*pos + 32].try_into().unwrap();
        *pos += 32;
        if present {
            Some(value)
        } else {
            None
        }
    };
    let dhr_public = take_opt(&mut pos);
    let cks = take_opt(&mut pos);
    let ckr = take_opt(&mut pos);

    let mut take_u32 = |pos: &mut usize| -> u32 {
        let value = u32::from_be_bytes(bytes[*pos..*pos + 4].try_into().unwrap());
        *pos += 4;
        value
    };
    let ns = take_u32(&mut pos);
    let nr = take_u32(&mut pos);
    let pn = take_u32(&mut pos);
    let count = take_u32(&mut pos) as usize;

    if count > MAX_SKIPPED_TOTAL || bytes.len() < pos + count * 68 {
        return Err(bad("lista de claves saltadas inconsistente"));
    }

    let mut skipped = HashMap::with_capacity(count);
    for _ in 0..count {
        let pubkey: [u8; 32] = bytes[pos..pos + 32].try_into().unwrap();
        pos += 32;
        let n = u32::from_be_bytes(bytes[pos..pos + 4].try_into().unwrap());
        pos += 4;
        let message_key: [u8; 32] = bytes[pos..pos + 32].try_into().unwrap();
        pos += 32;
        skipped.insert((pubkey, n), message_key);
    }

    Ok(Session {
        root_key,
        dhs_secret,
        dhs_public,
        dhr_public,
        cks,
        ckr,
        ns,
        nr,
        pn,
        skipped,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Monta una pareja lista para hablar.
    fn pair() -> (Vec<u8>, Vec<u8>) {
        let seed = vec![7u8; 32];
        let alice = ratchet_init_initiator(seed.clone()).unwrap();
        let bob = ratchet_init_responder(seed, alice.data.clone()).unwrap();
        (alice.session, bob)
    }

    #[test]
    fn conversacion_ida_y_vuelta() {
        let (mut alice, mut bob) = pair();

        let out = ratchet_encrypt(alice, b"hola bob".to_vec()).unwrap();
        alice = out.session;
        let opened = ratchet_decrypt(bob, out.data).unwrap();
        bob = opened.session;
        assert_eq!(opened.data, b"hola bob");

        // Bob responde: aquí entra el primer paso de ratchet DH.
        let out = ratchet_encrypt(bob, b"hola alice".to_vec()).unwrap();
        bob = out.session;
        let opened = ratchet_decrypt(alice, out.data).unwrap();
        alice = opened.session;
        assert_eq!(opened.data, b"hola alice");

        // Y seguimos varias rondas.
        for i in 0..5u8 {
            let msg = vec![i; 10];
            let out = ratchet_encrypt(alice, msg.clone()).unwrap();
            alice = out.session;
            let opened = ratchet_decrypt(bob, out.data).unwrap();
            bob = opened.session;
            assert_eq!(opened.data, msg);

            let out = ratchet_encrypt(bob, msg.clone()).unwrap();
            bob = out.session;
            let opened = ratchet_decrypt(alice, out.data).unwrap();
            alice = opened.session;
            assert_eq!(opened.data, msg);
        }
    }

    #[test]
    fn cada_mensaje_usa_una_clave_distinta() {
        let (mut alice, _bob) = pair();
        let a = ratchet_encrypt(alice.clone(), b"mismo texto".to_vec()).unwrap();
        alice = a.session;
        let b = ratchet_encrypt(alice, b"mismo texto".to_vec()).unwrap();

        // Mismo texto, ciphertext distinto: la cadena avanzó.
        assert_ne!(a.data, b.data);
    }

    #[test]
    fn mensajes_desordenados_se_abren_igual() {
        let (mut alice, mut bob) = pair();

        // Alice manda tres seguidos.
        let mut enviados = Vec::new();
        for i in 0..3u8 {
            let out = ratchet_encrypt(alice, vec![i; 5]).unwrap();
            alice = out.session;
            enviados.push(out.data);
        }

        // Llegan al revés: 3º, 1º, 2º.
        let opened = ratchet_decrypt(bob, enviados[2].clone()).unwrap();
        bob = opened.session;
        assert_eq!(opened.data, vec![2u8; 5]);

        let opened = ratchet_decrypt(bob, enviados[0].clone()).unwrap();
        bob = opened.session;
        assert_eq!(opened.data, vec![0u8; 5]);

        let opened = ratchet_decrypt(bob, enviados[1].clone()).unwrap();
        assert_eq!(opened.data, vec![1u8; 5]);
    }

    #[test]
    fn un_mensaje_perdido_no_rompe_la_conversacion() {
        let (mut alice, mut bob) = pair();

        let perdido = ratchet_encrypt(alice, b"este se pierde".to_vec()).unwrap();
        alice = perdido.session;
        let llega = ratchet_encrypt(alice, b"este llega".to_vec()).unwrap();

        // Bob nunca ve el primero y aun así abre el segundo.
        let opened = ratchet_decrypt(bob, llega.data).unwrap();
        bob = opened.session;
        assert_eq!(opened.data, b"este llega");

        // Y si el perdido aparece más tarde, también se abre.
        let opened = ratchet_decrypt(bob, perdido.data).unwrap();
        assert_eq!(opened.data, b"este se pierde");
    }

    #[test]
    fn forward_secrecy_la_clave_se_destruye_al_usarse() {
        let (alice, bob) = pair();

        let enviado = ratchet_encrypt(alice, b"secreto".to_vec()).unwrap();
        let opened = ratchet_decrypt(bob, enviado.data.clone()).unwrap();
        assert_eq!(opened.data, b"secreto");

        // Esta es la propiedad clave: con la sesión ya avanzada, el mismo
        // mensaje no vuelve a abrirse. Su clave se destruyó al usarse, así
        // que robar el estado de ahora no descifra lo que ya pasó.
        assert!(
            ratchet_decrypt(opened.session, enviado.data).is_err(),
            "una clave de mensaje ya usada no puede volver a servir",
        );
    }

    #[test]
    fn la_conversacion_se_cura_tras_un_compromiso() {
        let (mut alice, mut bob) = pair();

        // Un atacante roba el estado de Bob en este instante.
        let robado = bob.clone();

        // Alice y Bob siguen hablando: cada respuesta mete un paso de ratchet
        // DH, que inyecta aleatoriedad nueva que el atacante nunca vio.
        for _ in 0..2 {
            let out = ratchet_encrypt(alice, b"ping".to_vec()).unwrap();
            alice = out.session;
            bob = ratchet_decrypt(bob, out.data).unwrap().session;

            let out = ratchet_encrypt(bob, b"pong".to_vec()).unwrap();
            bob = out.session;
            alice = ratchet_decrypt(alice, out.data).unwrap().session;
        }

        // El estado robado ya no sirve para leer lo que se dicen ahora.
        let nuevo = ratchet_encrypt(alice, b"ya no puedes leer esto".to_vec()).unwrap();
        assert!(
            ratchet_decrypt(robado, nuevo.data).is_err(),
            "tras responderse, la sesión debe haberse curado",
        );
    }

    #[test]
    fn mensaje_manipulado_se_rechaza() {
        let (alice, bob) = pair();
        let mut out = ratchet_encrypt(alice, b"integridad".to_vec()).unwrap();
        let last = out.data.len() - 1;
        out.data[last] ^= 0x01;
        assert!(ratchet_decrypt(bob, out.data).is_err());
    }

    #[test]
    fn cabecera_manipulada_se_rechaza() {
        let (alice, bob) = pair();
        let mut out = ratchet_encrypt(alice, b"cabecera".to_vec()).unwrap();
        // Cambiar el número de mensaje debe invalidar el AEAD (va como AAD).
        out.data[39] ^= 0x01;
        assert!(ratchet_decrypt(bob, out.data).is_err());
    }

    #[test]
    fn un_salto_absurdo_se_rechaza_sin_agotar_memoria() {
        let (alice, bob) = pair();
        let out = ratchet_encrypt(alice, b"x".to_vec()).unwrap();
        let mut manipulado = out.data.clone();
        // Número de mensaje enorme: no debe intentar derivar 4.000 millones
        // de claves.
        manipulado[36..40].copy_from_slice(&u32::MAX.to_be_bytes());
        assert!(ratchet_decrypt(bob, manipulado).is_err());
    }

    #[test]
    fn la_sesion_sobrevive_a_guardarse_y_cargarse() {
        let (alice, bob) = pair();
        // Serializar y deserializar es lo que pasa en cada mensaje real,
        // porque el estado se persiste cifrado en el teléfono.
        let recargada = serialize(&deserialize(&alice).unwrap());
        let out = ratchet_encrypt(recargada, b"tras reiniciar".to_vec()).unwrap();
        let opened = ratchet_decrypt(bob, out.data).unwrap();
        assert_eq!(opened.data, b"tras reiniciar");
    }

    #[test]
    fn semilla_invalida_se_rechaza() {
        assert!(ratchet_init_initiator(vec![0u8; 16]).is_err());
        assert!(ratchet_init_responder(vec![0u8; 32], vec![0u8; 10]).is_err());
        assert!(ratchet_decrypt(vec![9u8; 50], vec![0u8; 100]).is_err());
    }
}
