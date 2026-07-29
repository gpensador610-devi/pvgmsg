use thiserror::Error;

/// Errores expuestos a través de FFI hacia la app Android.
#[derive(Debug, Error, uniffi::Error)]
pub enum CoreError {
    #[error("datos de invitación inválidos: {reason}")]
    InvalidInvite { reason: String },

    #[error("material de clave inválido: {reason}")]
    InvalidKey { reason: String },

    #[error("no se pudo descifrar el mensaje")]
    DecryptionFailed,

    #[error("mensaje sellado malformado: {reason}")]
    MalformedMessage { reason: String },

    #[error("frase de recuperación inválida: {reason}")]
    InvalidMnemonic { reason: String },

    #[error("copia de seguridad: {reason}")]
    BackupFailed { reason: String },

    #[error("sesión de ratchet: {reason}")]
    RatchetFailed { reason: String },
}
