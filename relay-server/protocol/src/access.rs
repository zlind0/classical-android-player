//! Access scopes shared by the NAS, relay and agent.
//!
//! A scope is the set of library root ids a device may see through the relay.
//! Root ids are opaque to the relay; a scope is identified by a stable hash of
//! the sorted id list so that every side computes the same snapshot namespace.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Scope {
    /// Stable hash of `roots`, used as the relay's snapshot namespace.
    pub id: u64,
    /// Sorted, deduplicated library root ids.
    pub roots: Vec<i64>,
    /// True when `roots` covers every root currently configured on the NAS.
    pub all: bool,
}

impl Scope {
    pub fn new(roots: Vec<i64>, all: bool) -> Scope {
        let roots = normalize_roots(roots);
        let id = scope_id(&roots);
        Scope { id, roots, all }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthorizedDevice {
    pub id: [u8; 16],
    pub label: String,
    pub fingerprint: String,
    #[serde(default)]
    pub roots: Vec<i64>,
    #[serde(default)]
    pub all: bool,
}

pub fn normalize_roots(mut roots: Vec<i64>) -> Vec<i64> {
    roots.retain(|root| *root > 0);
    roots.sort_unstable();
    roots.dedup();
    roots
}

/// Stable 64-bit identifier for a root set. The empty set is always 0.
pub fn scope_id(roots: &[i64]) -> u64 {
    if roots.is_empty() {
        return 0;
    }
    let mut hash = 0xcbf2_9ce4_8422_2325u64;
    for root in roots {
        for byte in root.to_be_bytes() {
            hash ^= byte as u64;
            hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
        }
    }
    if hash == 0 {
        1
    } else {
        hash
    }
}

/// Version shown to the client for a restricted snapshot. It must change when
/// either the library or the root set changes, and stay stable otherwise.
pub fn scope_version(library_version: i64, scope_id: u64) -> i64 {
    let mut hasher = Sha256::new();
    hasher.update(b"podrelay-scope-v1");
    hasher.update(library_version.to_be_bytes());
    hasher.update(scope_id.to_be_bytes());
    let digest = hasher.finalize();
    let mut bytes = [0u8; 8];
    bytes.copy_from_slice(&digest[..8]);
    let value = i64::from_be_bytes(bytes) & i64::MAX;
    if value == 0 {
        1
    } else {
        value
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roots_are_normalized() {
        assert_eq!(normalize_roots(vec![3, 1, 3, 0, -1]), vec![1, 3]);
        assert_eq!(Scope::new(vec![3, 1, 3], true).roots, vec![1, 3]);
    }

    #[test]
    fn scope_ids_are_stable_and_order_independent() {
        let a = scope_id(&normalize_roots(vec![7, 2, 9]));
        let b = scope_id(&normalize_roots(vec![9, 7, 2]));
        assert_eq!(a, b);
        assert_ne!(a, scope_id(&[2, 7]));
        assert_eq!(scope_id(&[]), 0);
    }

    #[test]
    fn scope_version_changes_with_library_and_scope() {
        assert_ne!(scope_version(1, 5), scope_version(2, 5));
        assert_ne!(scope_version(1, 5), scope_version(1, 6));
        assert_eq!(scope_version(1, 5), scope_version(1, 5));
        assert!(scope_version(0, 0) > 0);
    }
}
