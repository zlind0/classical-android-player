//! Application-layer crypto. The relay server never holds `media_key`; it only
//! stores ciphertext produced here.

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use hmac::{Hmac, Mac};
use rand::RngCore;
use sha2::Sha256;

use crate::error::{Error, Result};
use crate::msg::FileId;

pub const FRAGMENT_BYTES: usize = 256 * 1024;
pub const TAG_BYTES: usize = 16;

const LABEL_ID: &[u8] = b"pod-relay/v1/id";
const LABEL_MEDIA: &[u8] = b"pod-relay/v1/media";
const LABEL_META: &[u8] = b"pod-relay/v1/meta";
const LABEL_LIVE: &[u8] = b"pod-relay/v1/live";

pub type MediaKey = [u8; 32];

fn hmac(key: &[u8], data: &[u8]) -> [u8; 32] {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("hmac accepts any key");
    mac.update(data);
    mac.finalize().into_bytes().into()
}

pub fn derive(master: &MediaKey, label: &[u8]) -> [u8; 32] {
    hmac(master, label)
}

pub fn key_id(master: &MediaKey) -> u64 {
    let digest = hmac(master, LABEL_ID);
    u64::from_be_bytes(digest[0..8].try_into().unwrap())
}

pub fn media_key(master: &MediaKey) -> [u8; 32] {
    derive(master, LABEL_MEDIA)
}

pub fn meta_key(master: &MediaKey) -> [u8; 32] {
    derive(master, LABEL_META)
}

pub fn live_key(master: &MediaKey) -> [u8; 32] {
    derive(master, LABEL_LIVE)
}

pub fn random_salt() -> [u8; 8] {
    let mut salt = [0u8; 8];
    rand::thread_rng().fill_bytes(&mut salt);
    salt
}

/// Deterministic per-file salt so repushing after a relay eviction never
/// changes the nonce base of already-cached fragments.
pub fn file_salt(key: &[u8; 32], file_id: &FileId) -> [u8; 8] {
    let mut data = Vec::with_capacity(4 + 16);
    data.extend_from_slice(b"salt");
    data.extend_from_slice(file_id);
    let digest = hmac(key, &data);
    let mut salt = [0u8; 8];
    salt.copy_from_slice(&digest[0..8]);
    salt
}

pub fn fragment_nonce(index: u32, salt: &[u8; 8]) -> [u8; 12] {
    let mut nonce = [0u8; 12];
    nonce[0..4].copy_from_slice(&index.to_be_bytes());
    nonce[4..12].copy_from_slice(salt);
    nonce
}

pub fn fragment_aad(file_id: &FileId, index: u32) -> Vec<u8> {
    let mut aad = Vec::with_capacity(20);
    aad.extend_from_slice(file_id);
    aad.extend_from_slice(&index.to_be_bytes());
    aad
}

pub fn seal(key: &[u8; 32], nonce: &[u8; 12], aad: &[u8], plain: &[u8]) -> Result<Vec<u8>> {
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| Error::Crypto)?;
    cipher
        .encrypt(Nonce::from_slice(nonce), Payload { msg: plain, aad })
        .map_err(|_| Error::Crypto)
}

pub fn open(key: &[u8; 32], nonce: &[u8; 12], aad: &[u8], ct: &[u8]) -> Result<Vec<u8>> {
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| Error::Crypto)?;
    cipher
        .decrypt(Nonce::from_slice(nonce), Payload { msg: ct, aad })
        .map_err(|_| Error::Crypto)
}

pub fn encrypt_fragment(
    key: &[u8; 32],
    file_id: &FileId,
    salt: &[u8; 8],
    index: u32,
    plain: &[u8],
) -> Result<([u8; 12], Vec<u8>)> {
    let nonce = fragment_nonce(index, salt);
    let aad = fragment_aad(file_id, index);
    let ct = seal(key, &nonce, &aad, plain)?;
    Ok((nonce, ct))
}

pub fn decrypt_fragment(
    key: &[u8; 32],
    file_id: &FileId,
    index: u32,
    nonce: &[u8; 12],
    ct: &[u8],
) -> Result<Vec<u8>> {
    let aad = fragment_aad(file_id, index);
    open(key, nonce, &aad, ct)
}

pub fn live_seal(key: &[u8; 32], seq: u32, dir: u8, plain: &[u8]) -> Result<Vec<u8>> {
    let mut nonce = [0u8; 12];
    rand::thread_rng().fill_bytes(&mut nonce);
    let mut aad = seq.to_be_bytes().to_vec();
    aad.push(dir);
    let ct = seal(key, &nonce, &aad, plain)?;
    let mut out = Vec::with_capacity(12 + ct.len());
    out.extend_from_slice(&nonce);
    out.extend_from_slice(&ct);
    Ok(out)
}

pub fn live_open(key: &[u8; 32], seq: u32, dir: u8, data: &[u8]) -> Result<Vec<u8>> {
    if data.len() < 12 + TAG_BYTES {
        return Err(Error::Crypto);
    }
    let nonce: [u8; 12] = data[0..12].try_into().unwrap();
    let mut aad = seq.to_be_bytes().to_vec();
    aad.push(dir);
    open(key, &nonce, &aad, &data[12..])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fragment_roundtrip_and_tamper() {
        let master: MediaKey = [9u8; 32];
        let key = media_key(&master);
        let file_id = [3u8; 16];
        let salt = random_salt();
        let (nonce, ct) = encrypt_fragment(&key, &file_id, &salt, 5, b"hello").unwrap();
        assert_eq!(
            decrypt_fragment(&key, &file_id, 5, &nonce, &ct).unwrap(),
            b"hello"
        );
        let mut broken = ct.clone();
        broken[0] ^= 0xff;
        assert!(decrypt_fragment(&key, &file_id, 5, &nonce, &broken).is_err());
        assert!(decrypt_fragment(&key, &file_id, 6, &nonce, &ct).is_err());
    }

    #[test]
    fn key_id_is_stable() {
        let master: MediaKey = [1u8; 32];
        assert_eq!(key_id(&master), key_id(&master));
        assert_ne!(key_id(&master), key_id(&[2u8; 32]));
    }
}
