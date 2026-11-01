//! TLS setup with certificate pinning (no CA, no PKI).

use crate::cert::{fingerprint_der, load_certs, load_key};
use anyhow::{anyhow, Result};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, DistinguishedName, SignatureScheme};
use std::collections::HashSet;
use std::sync::Arc;

pub fn install_provider() {
    let _ = rustls::crypto::ring::default_provider().install_default();
}

fn provider() -> Arc<rustls::crypto::CryptoProvider> {
    install_provider();
    Arc::new(rustls::crypto::ring::default_provider())
}

#[derive(Debug)]
pub struct PinnedServerVerifier {
    expected: String,
    provider: Arc<rustls::crypto::CryptoProvider>,
}

impl PinnedServerVerifier {
    pub fn new(expected: &str) -> Self {
        PinnedServerVerifier {
            expected: expected.to_string(),
            provider: provider(),
        }
    }
}

impl ServerCertVerifier for PinnedServerVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let got = fingerprint_der(end_entity.as_ref());
        if got == self.expected {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General(format!(
                "relay certificate pin mismatch: expected {}, got {}",
                self.expected, got
            )))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

#[derive(Debug)]
pub struct AllowListClientVerifier {
    allowed: HashSet<String>,
    provider: Arc<rustls::crypto::CryptoProvider>,
}

impl AllowListClientVerifier {
    pub fn new(allowed: HashSet<String>) -> Self {
        AllowListClientVerifier {
            allowed,
            provider: provider(),
        }
    }
}

impl ClientCertVerifier for AllowListClientVerifier {
    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }

    fn verify_client_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _now: UnixTime,
    ) -> Result<ClientCertVerified, rustls::Error> {
        let got = fingerprint_der(end_entity.as_ref());
        if self.allowed.contains(&got) {
            Ok(ClientCertVerified::assertion())
        } else {
            Err(rustls::Error::General(format!(
                "client certificate not allowed: {got}"
            )))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }

    fn offer_client_auth(&self) -> bool {
        true
    }

    fn client_auth_mandatory(&self) -> bool {
        true
    }
}

pub fn server_config(cert: &std::path::Path, key: &std::path::Path, allowed: HashSet<String>) -> Result<Arc<rustls::ServerConfig>> {
    if allowed.is_empty() {
        return Err(anyhow!(
            "no host/client fingerprints configured; refusing to start"
        ));
    }
    let certs = load_certs(cert)?;
    let key = load_key(key)?;
    let provider = provider();
    let verifier = AllowListClientVerifier::new(allowed);
    let config = rustls::ServerConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()?
        .with_client_cert_verifier(Arc::new(verifier))
        .with_single_cert(certs, key)?;
    Ok(Arc::new(config))
}

pub fn client_config(
    ca_pin: &str,
    cert: &std::path::Path,
    key: &std::path::Path,
) -> Result<Arc<rustls::ClientConfig>> {
    let certs = load_certs(cert)?;
    let key = load_key(key)?;
    let provider = provider();
    let verifier = PinnedServerVerifier::new(ca_pin);
    let config = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(verifier))
        .with_client_auth_cert(certs, key)?;
    Ok(Arc::new(config))
}
