//! Self-signed certificate helpers: generation (rcgen), PEM loading, fingerprint
//! pinning. Certificates are pinned by the SHA-256 of the end-entity DER.

use anyhow::{anyhow, Context, Result};
use base64::Engine;
use rcgen::{CertificateParams, DnType, KeyPair, SanType};
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use sha2::{Digest, Sha256};
use std::io::BufReader;
use std::net::IpAddr;
use std::path::{Path, PathBuf};

pub const PIN_PREFIX: &str = "sha256/";

pub fn fingerprint_der(der: &[u8]) -> String {
    let digest = Sha256::digest(der);
    format!(
        "{PIN_PREFIX}{}",
        base64::engine::general_purpose::STANDARD.encode(digest)
    )
}

pub fn load_certs(path: &Path) -> Result<Vec<CertificateDer<'static>>> {
    let file = std::fs::File::open(path)
        .with_context(|| format!("open cert {}", path.display()))?;
    let mut reader = BufReader::new(file);
    let certs: Vec<_> = rustls_pemfile::certs(&mut reader)
        .collect::<std::result::Result<_, _>>()
        .context("parse certificate PEM")?;
    if certs.is_empty() {
        return Err(anyhow!("no certificate in {}", path.display()));
    }
    Ok(certs)
}

pub fn load_key(path: &Path) -> Result<PrivateKeyDer<'static>> {
    let file = std::fs::File::open(path)
        .with_context(|| format!("open key {}", path.display()))?;
    let mut reader = BufReader::new(file);
    rustls_pemfile::private_key(&mut reader)
        .context("parse private key PEM")?
        .ok_or_else(|| anyhow!("no private key in {}", path.display()))
}

pub fn fingerprint_file(path: &Path) -> Result<String> {
    let certs = load_certs(path)?;
    Ok(fingerprint_der(certs[0].as_ref()))
}

/// Parses SAN specs such as `dns:relay.example.com` or `ip:203.0.113.10`.
fn parse_sans(sans: &[String]) -> Result<Vec<SanType>> {
    let mut out = Vec::new();
    for raw in sans {
        if let Some(rest) = raw.strip_prefix("dns:") {
            out.push(SanType::DnsName(rest.to_string().try_into()?));
        } else if let Some(rest) = raw.strip_prefix("ip:") {
            let ip: IpAddr = rest.parse().with_context(|| format!("bad ip san {rest}"))?;
            out.push(SanType::IpAddress(ip));
        } else {
            out.push(SanType::DnsName(raw.clone().try_into()?));
        }
    }
    Ok(out)
}

/// Generates a self-signed certificate. Writes `<out>.crt`/`<out>.key`.
pub fn generate(role: &str, cn: &str, sans: &[String], out: &Path) -> Result<PathBuf> {
    let mut params = CertificateParams::new(vec![cn.to_string()])?;
    params.distinguished_name.push(DnType::CommonName, cn);
    params.subject_alt_names = parse_sans(sans)?;
    if params.subject_alt_names.is_empty() {
        params
            .subject_alt_names
            .push(SanType::DnsName(cn.to_string().try_into()?));
    }
    params.is_ca = rcgen::IsCa::NoCa;

    let key_pair = KeyPair::generate()?;
    let cert = params.self_signed(&key_pair)?;

    if let Some(parent) = out.parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent)?;
        }
    }
    let crt_path = out.with_extension("crt");
    let key_path = out.with_extension("key");
    std::fs::write(&crt_path, cert.pem())?;
    std::fs::write(&key_path, key_pair.serialize_pem())?;
    eprintln!(
        "[pod-relay] generated {role} certificate: {} / {}",
        crt_path.display(),
        key_path.display()
    );
    Ok(crt_path)
}
