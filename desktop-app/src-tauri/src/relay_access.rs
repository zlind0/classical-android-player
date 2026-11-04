//! Folder-level access checks for relay live requests.
//!
//! Media bytes already go through the relay's `file_id` ACL. Live calls are the
//! other half: they reach the NAS directly through the encrypted tunnel, so the
//! NAS has to repeat the check for the requesting device.

use crate::{db, http_server};
use serde_json::Value;
use std::collections::HashMap;
use std::sync::{Arc, RwLock};

#[derive(Debug, Clone, Default)]
pub struct Grants {
    pub roots: Vec<i64>,
    pub all: bool,
}

impl Grants {
    pub fn allows(&self, root: i64) -> bool {
        self.all || self.roots.contains(&root)
    }
}

/// Shared snapshot of the device grants that `RelayManager` keeps in
/// `devices.json`. The HTTP backend reads it without touching the relay handle.
#[derive(Default)]
pub struct DeviceGrants {
    devices: RwLock<HashMap<[u8; 16], Grants>>,
    feeds: RwLock<HashMap<String, [u8; 16]>>,
}

pub type SharedGrants = Arc<DeviceGrants>;

impl DeviceGrants {
    pub fn new() -> SharedGrants {
        Arc::new(DeviceGrants::default())
    }

    pub fn replace<I>(&self, entries: I)
    where
        I: IntoIterator<Item = ([u8; 16], Vec<i64>, bool)>,
    {
        let next: HashMap<[u8; 16], Grants> = entries
            .into_iter()
            .map(|(id, roots, all)| {
                let mut roots = roots;
                roots.sort_unstable();
                roots.dedup();
                (id, Grants { roots, all })
            })
            .collect();
        if let Ok(mut slot) = self.devices.write() {
            *slot = next;
        }
    }

    pub fn remove(&self, id: &[u8; 16]) {
        if let Ok(mut slot) = self.devices.write() {
            slot.remove(id);
        }
        if let Ok(mut feeds) = self.feeds.write() {
            feeds.retain(|_, owner| owner != id);
        }
    }

    pub fn clear(&self) {
        if let Ok(mut slot) = self.devices.write() {
            slot.clear();
        }
        if let Ok(mut feeds) = self.feeds.write() {
            feeds.clear();
        }
    }

    fn grants(&self, id: &[u8; 16]) -> Option<Grants> {
        self.devices.read().ok()?.get(id).cloned()
    }

    /// Whether a relay client may read books from `root`.
    pub fn allows(&self, id: &[u8; 16], root: i64) -> bool {
        self.grants(id).map(|grant| grant.allows(root)).unwrap_or(false)
    }

    fn claim_feed(&self, session: &str, id: [u8; 16]) {
        if session.is_empty() {
            return;
        }
        if let Ok(mut feeds) = self.feeds.write() {
            if feeds.len() > 512 {
                feeds.clear();
            }
            feeds.insert(session.to_string(), id);
        }
    }

    fn owns_feed(&self, session: &str, id: &[u8; 16]) -> bool {
        self.feeds
            .read()
            .ok()
            .and_then(|feeds| feeds.get(session).copied())
            .map(|owner| owner == *id)
            .unwrap_or(false)
    }

    fn release_feed(&self, session: &str) {
        if let Ok(mut feeds) = self.feeds.write() {
            feeds.remove(session);
        }
    }
}

pub struct Decision {
    /// Feed session closed by this request.
    pub release_feed: Option<String>,
}

impl Decision {
    fn allow() -> Decision {
        Decision {
            release_feed: None,
        }
    }
}

pub struct Denied {
    pub status: u16,
    pub message: String,
}

fn denied(message: impl Into<String>) -> Denied {
    Denied {
        status: 403,
        message: message.into(),
    }
}

/// Checks one live request. `client_id == None` means a local (LAN) caller and
/// is always allowed: folder ACLs only protect the public relay path.
pub async fn check(
    state: &http_server::HttpState,
    grants: &DeviceGrants,
    client_id: Option<[u8; 16]>,
    method: &str,
    path: &str,
    body: &[u8],
) -> Result<Decision, Denied> {
    let Some(client_id) = client_id else {
        return Ok(Decision::allow());
    };
    let Some(grant) = grants.grants(&client_id) else {
        return Err(denied("device has no relay access"));
    };

    let (route, query) = path.split_once('?').unwrap_or((path, ""));
    let params = parse_query(query);

    match route {
        "/library/ebook/random" | "/library/ebook/search" => {
            let root = params
                .get("rootKey")
                .and_then(|text| text.parse::<i64>().ok())
                .ok_or_else(|| denied("missing rootKey"))?;
            if !grant.allows(root) {
                return Err(denied("folder is not shared with this device"));
            }
            Ok(Decision::allow())
        }
        "/library/ebook/scan" => {
            let paths: Vec<String> = if method == "GET" {
                vec![params
                    .get("path")
                    .cloned()
                    .ok_or_else(|| denied("missing path"))?]
            } else {
                serde_json::from_slice::<Value>(body)
                    .ok()
                    .and_then(|value| {
                        value.get("paths").and_then(|v| v.as_array()).map(|items| {
                            items
                                .iter()
                                .filter_map(|item| item.as_str().map(str::to_string))
                                .collect()
                        })
                    })
                    .ok_or_else(|| denied("missing paths"))?
            };
            if paths.is_empty() {
                return Ok(Decision::allow());
            }
            let db = state.db.clone();
            let roots = tokio::task::spawn_blocking(move || {
                let conn = db.lock();
                paths
                    .iter()
                    .map(|path| db::book_root_id(&conn, path))
                    .collect::<anyhow::Result<Vec<_>>>()
            })
            .await
            .map_err(|err| denied(format!("database task failed: {err}")))?
            .map_err(|err| denied(err.to_string()))?;
            if roots
                .iter()
                .all(|root| root.map(|root| grant.allows(root)).unwrap_or(false))
            {
                Ok(Decision::allow())
            } else {
                Err(denied("book is not shared with this device"))
            }
        }
        "/library/ebook/feed/start" => {
            let root = serde_json::from_slice::<Value>(body)
                .ok()
                .and_then(|value| value.get("rootKey").and_then(|v| v.as_str()).map(str::to_string))
                .and_then(|text| text.parse::<i64>().ok())
                .ok_or_else(|| denied("missing rootKey"))?;
            if !grant.allows(root) {
                return Err(denied("folder is not shared with this device"));
            }
            Ok(Decision::allow())
        }
        "/library/ebook/feed/turn" | "/library/ebook/feed/close" => {
            let session = serde_json::from_slice::<Value>(body)
                .ok()
                .and_then(|value| {
                    value
                        .get("sessionId")
                        .and_then(|v| v.as_str())
                        .map(str::to_string)
                })
                .unwrap_or_default();
            if session.is_empty() || !grants.owns_feed(&session, &client_id) {
                return Err(denied("reading session belongs to another device"));
            }
            Ok(Decision {
                release_feed: if route.ends_with("close") {
                    Some(session)
                } else {
                    None
                },
            })
        }
        "/library/ebook/feed/chapter" => {
            let session = params.get("sessionId").cloned().unwrap_or_default();
            if session.is_empty() || !grants.owns_feed(&session, &client_id) {
                return Err(denied("reading session belongs to another device"));
            }
            Ok(Decision::allow())
        }
        _ => Err(denied("endpoint is not available through the relay")),
    }
}

/// Claims the session id from a successful `feed/start` response body.
pub fn claim_from_response(
    grants: &DeviceGrants,
    client_id: Option<[u8; 16]>,
    body: &[u8],
) {
    let (Some(client_id), Ok(value)) = (client_id, serde_json::from_slice::<Value>(body)) else {
        return;
    };
    if let Some(session) = value.get("sessionId").and_then(|v| v.as_str()) {
        grants.claim_feed(session, client_id);
    }
}

pub fn release_session(grants: &DeviceGrants, session: &str) {
    grants.release_feed(session);
}

fn parse_query(query: &str) -> HashMap<String, String> {
    let mut out = HashMap::new();
    for part in query.split('&') {
        if part.is_empty() {
            continue;
        }
        let (key, value) = part.split_once('=').unwrap_or((part, ""));
        out.insert(decode(key), decode(value));
    }
    out
}

fn decode(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        match bytes[index] {
            b'+' => {
                out.push(b' ');
                index += 1;
            }
            b'%' if index + 2 < bytes.len() => {
                let hex = &text[index + 1..index + 3];
                if let Ok(byte) = u8::from_str_radix(hex, 16) {
                    out.push(byte);
                    index += 3;
                } else {
                    out.push(bytes[index]);
                    index += 1;
                }
            }
            byte => {
                out.push(byte);
                index += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn query_decoding_handles_plus_and_percent() {
        let params = parse_query("path=%2Fa+b.epub&rootKey=3");
        assert_eq!(params.get("path").map(String::as_str), Some("/a b.epub"));
        assert_eq!(params.get("rootKey").map(String::as_str), Some("3"));
    }

    #[test]
    fn grants_respect_roots_and_all() {
        let grants = DeviceGrants::default();
        grants.replace([([1u8; 16], vec![2, 3], false)]);
        let grant = grants.grants(&[1u8; 16]).unwrap();
        assert!(grant.allows(2));
        assert!(!grant.allows(4));
        grants.replace([([1u8; 16], vec![], true)]);
        assert!(grants.grants(&[1u8; 16]).unwrap().allows(99));
    }

    #[test]
    fn feed_ownership_is_per_device() {
        let grants = DeviceGrants::default();
        grants.claim_feed("s1", [1u8; 16]);
        assert!(grants.owns_feed("s1", &[1u8; 16]));
        assert!(!grants.owns_feed("s1", &[2u8; 16]));
        grants.remove(&[1u8; 16]);
        assert!(!grants.owns_feed("s1", &[1u8; 16]));
    }
}
