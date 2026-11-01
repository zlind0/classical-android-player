//! Library snapshot as stored on the relay: gzip + independently encrypted
//! 512 KiB chunks. The relay only parses the plaintext header.

use crate::crypto::{open, random_salt, seal, MediaKey};
use crate::error::{Result, Error};
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use flate2::Compression;
use std::io::{Read, Write};

pub const SNAPSHOT_CHUNK: usize = 512 * 1024;
pub const MAGIC: &[u8; 8] = b"AURLIB1\0";

#[derive(Debug, Clone, Copy)]
pub struct SnapshotHeader {
    pub version: i64,
    pub key_id: u64,
    pub plain_len: u64,
    pub gz_len: u64,
    pub chunks: u32,
    pub salt: [u8; 8],
}

fn chunk_aad(version: i64, chunk_index: u32) -> Vec<u8> {
    let mut aad = Vec::with_capacity(16);
    aad.extend_from_slice(MAGIC);
    aad.extend_from_slice(&version.to_be_bytes());
    aad.extend_from_slice(&chunk_index.to_be_bytes());
    aad
}

fn chunk_nonce(chunk_index: u32, salt: &[u8; 8]) -> [u8; 12] {
    let mut nonce = [0u8; 12];
    nonce[0..4].copy_from_slice(&chunk_index.to_be_bytes());
    nonce[4..12].copy_from_slice(salt);
    nonce
}

fn gzip(data: &[u8]) -> Result<Vec<u8>> {
    let mut enc = GzEncoder::new(Vec::new(), Compression::default());
    enc.write_all(data)?;
    Ok(enc.finish()?)
}

fn gunzip(data: &[u8]) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    GzDecoder::new(data).read_to_end(&mut out)?;
    Ok(out)
}

/// Encrypts a snapshot JSON. Returns the plaintext header plus the ciphertext
/// chunks (each already includes the GCM tag).
pub fn pack(key: &[u8; 32], version: i64, key_id: u64, json: &[u8]) -> Result<(SnapshotHeader, Vec<Vec<u8>>)> {
    let gz = gzip(json)?;
    let chunks = gz.len().div_ceil(SNAPSHOT_CHUNK) as u32;
    let salt = random_salt();
    let mut out = Vec::with_capacity(chunks as usize);
    for index in 0..chunks {
        let start = index as usize * SNAPSHOT_CHUNK;
        let end = (start + SNAPSHOT_CHUNK).min(gz.len());
        let nonce = chunk_nonce(index, &salt);
        let aad = chunk_aad(version, index);
        out.push(seal(key, &nonce, &aad, &gz[start..end])?);
    }
    let header = SnapshotHeader {
        version,
        key_id,
        plain_len: json.len() as u64,
        gz_len: gz.len() as u64,
        chunks,
        salt,
    };
    Ok((header, out))
}

pub fn unpack(key: &[u8; 32], header: &SnapshotHeader, chunks: &[Vec<u8>]) -> Result<Vec<u8>> {
    if chunks.len() != header.chunks as usize {
        return Err(Error::Protocol("snapshot chunk count mismatch".into()));
    }
    let mut gz = Vec::with_capacity(header.gz_len as usize);
    for (index, ct) in chunks.iter().enumerate() {
        let index = index as u32;
        let nonce = chunk_nonce(index, &header.salt);
        let aad = chunk_aad(header.version, index);
        gz.extend_from_slice(&open(key, &nonce, &aad, ct)?);
    }
    if gz.len() as u64 != header.gz_len {
        return Err(Error::Protocol("snapshot gz length mismatch".into()));
    }
    let json = gunzip(&gz)?;
    if json.len() as u64 != header.plain_len {
        return Err(Error::Protocol("snapshot plain length mismatch".into()));
    }
    Ok(json)
}

pub fn key_id_matches(master: &MediaKey, expected: u64) -> bool {
    crate::crypto::key_id(master) == expected
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snapshot_roundtrip() {
        let master: MediaKey = [4u8; 32];
        let key = crate::crypto::meta_key(&master);
        let json: Vec<u8> = (0..(SNAPSHOT_CHUNK * 2 + 7))
            .map(|_| rand::random::<u8>())
            .collect();
        let (header, chunks) = pack(&key, 12, crate::crypto::key_id(&master), &json).unwrap();
        assert_eq!(header.chunks, 3);
        assert_eq!(unpack(&key, &header, &chunks).unwrap(), json);

        let mut broken = chunks.clone();
        broken[1][0] ^= 1;
        assert!(unpack(&key, &header, &broken).is_err());
    }
}
