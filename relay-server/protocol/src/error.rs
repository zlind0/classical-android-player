use thiserror::Error;

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug, Error)]
pub enum Error {
    #[error("protocol error: {0}")]
    Protocol(String),
    #[error("crypto error")]
    Crypto,
    #[error("not found")]
    NotFound,
    #[error("throttled")]
    Throttled,
    #[error("host offline")]
    HostOffline,
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("json: {0}")]
    Json(#[from] serde_json::Error),
}

impl From<aes_gcm::Error> for Error {
    fn from(_: aes_gcm::Error) -> Self {
        Error::Crypto
    }
}
