use crate::models::ServerInfo;
use mdns_sd::{ServiceDaemon, ServiceInfo as MdnsServiceInfo};
use serde_json::json;
use std::collections::HashMap;
use std::net::UdpSocket;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

const SERVICE_TYPE: &str = "_aurora-cast._tcp.local.";
const DISCOVERY_PORT: u16 = 47823;
const OFFER_INTERVAL: Duration = Duration::from_secs(10);

/// Keeps the mDNS daemon and UDP responder alive for the lifetime of the app.
pub struct Discovery {
    stop: Arc<AtomicBool>,
    daemon: Option<ServiceDaemon>,
}

impl Drop for Discovery {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(daemon) = &self.daemon {
            let _ = daemon.shutdown();
        }
    }
}

fn local_ipv4() -> String {
    let socket = match UdpSocket::bind("0.0.0.0:0") {
        Ok(socket) => socket,
        Err(_) => return "127.0.0.1".to_string(),
    };
    if socket.connect("8.8.8.8:80").is_err() {
        return "127.0.0.1".to_string();
    }
    socket
        .local_addr()
        .map(|addr| addr.ip().to_string())
        .unwrap_or_else(|_| "127.0.0.1".to_string())
}

fn host_name() -> String {
    let host = std::env::var("HOSTNAME")
        .or_else(|_| std::env::var("COMPUTERNAME"))
        .unwrap_or_else(|_| "aurora-desktop".to_string());
    let host: String = host
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' {
                c
            } else {
                '-'
            }
        })
        .collect();
    format!("{}.local.", host.trim_matches('-'))
}

fn start_mdns(info: &ServerInfo) -> Option<ServiceDaemon> {
    let daemon = ServiceDaemon::new().ok()?;
    let short: String = info.device_id.chars().take(6).collect();
    let instance = format!("Aurora-{short}");
    let properties: HashMap<String, String> = HashMap::from([
        ("v".to_string(), "1".to_string()),
        ("id".to_string(), info.device_id.clone()),
        ("name".to_string(), info.device_name.clone()),
        ("state".to_string(), "idle".to_string()),
    ]);
    let service = MdnsServiceInfo::new(
        SERVICE_TYPE,
        &instance,
        &host_name(),
        local_ipv4().as_str(),
        info.port,
        properties,
    )
    .ok()?
    .enable_addr_auto();

    if let Err(err) = daemon.register(service) {
        eprintln!("[aurora] mDNS registration failed: {err}");
        return None;
    }
    Some(daemon)
}

fn start_udp(info: &ServerInfo, stop: Arc<AtomicBool>) {
    let device_id = info.device_id.clone();
    let device_name = info.device_name.clone();
    let port = info.port;

    std::thread::Builder::new()
        .name("aurora-udp-offer".into())
        .spawn(move || {
            let socket = match UdpSocket::bind(("0.0.0.0", DISCOVERY_PORT)) {
                Ok(socket) => socket,
                Err(err) => {
                    eprintln!(
                        "[aurora] UDP :{DISCOVERY_PORT} unavailable, discovery is mDNS-only: {err}"
                    );
                    return;
                }
            };
            let _ = socket.set_broadcast(true);
            let _ = socket.set_read_timeout(Some(Duration::from_millis(500)));

            let offer = json!({
                "t": "offer",
                "v": 1,
                "id": device_id,
                "name": device_name,
                "port": port,
                "state": "idle",
            })
            .to_string();

            let mut buffer = [0u8; 4096];
            let mut last_offer: Option<Instant> = None;

            while !stop.load(Ordering::SeqCst) {
                match socket.recv_from(&mut buffer) {
                    Ok((size, source)) => {
                        let text = String::from_utf8_lossy(&buffer[..size]);
                        if let Ok(value) = serde_json::from_str::<serde_json::Value>(text.trim()) {
                            let kind = value.get("t").and_then(|v| v.as_str()).unwrap_or("");
                            let peer_id = value.get("id").and_then(|v| v.as_str()).unwrap_or("");
                            let valid = value.get("v").and_then(|v| v.as_i64()) == Some(1)
                                && !peer_id.is_empty()
                                && peer_id != device_id;
                            if valid && kind == "probe" {
                                let _ = socket.send_to(offer.as_bytes(), source);
                            }
                        }
                    }
                    Err(err)
                        if err.kind() == std::io::ErrorKind::WouldBlock
                            || err.kind() == std::io::ErrorKind::TimedOut => {}
                    Err(err) => {
                        eprintln!("[aurora] UDP receive error: {err}");
                    }
                }

                let should_offer = last_offer
                    .map(|last| last.elapsed() >= OFFER_INTERVAL)
                    .unwrap_or(true);
                if should_offer {
                    let _ = socket.send_to(offer.as_bytes(), ("255.255.255.255", DISCOVERY_PORT));
                    last_offer = Some(Instant::now());
                }
            }
        })
        .ok();
}

pub fn start(info: &ServerInfo) -> Discovery {
    let stop = Arc::new(AtomicBool::new(false));
    let daemon = start_mdns(info);
    start_udp(info, stop.clone());
    Discovery { stop, daemon }
}
