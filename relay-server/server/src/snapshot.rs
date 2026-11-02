//! Versioned storage for encrypted library snapshots. Keeps the current and the
//! previous version so a client mid-download never mixes versions.

use anyhow::Result;
use pod_relay_protocol::snapshot::SnapshotHeader;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize)]
struct SnapshotMeta {
    version: i64,
    key_id: u64,
    plain_len: u64,
    gz_len: u64,
    chunks: u32,
    salt: [u8; 8],
}

pub struct SnapshotStore {
    root: PathBuf,
}

impl SnapshotStore {
    pub fn open(root: &Path) -> Result<Self> {
        fs::create_dir_all(root.join("snap"))?;
        Ok(SnapshotStore {
            root: root.join("snap"),
        })
    }

    fn scope_dir(&self, scope: u64) -> PathBuf {
        self.root.join(format!("{scope:016x}"))
    }

    fn version_dir(&self, scope: u64, version: i64) -> PathBuf {
        self.scope_dir(scope).join(version.to_string())
    }

    pub fn put(&self, scope: u64, header: &SnapshotHeader, chunks: &[Vec<u8>]) -> Result<()> {
        let dir = self.version_dir(scope, header.version);
        fs::create_dir_all(&dir)?;
        let meta = SnapshotMeta {
            version: header.version,
            key_id: header.key_id,
            plain_len: header.plain_len,
            gz_len: header.gz_len,
            chunks: header.chunks,
            salt: header.salt,
        };
        for (index, ct) in chunks.iter().enumerate() {
            fs::write(dir.join(format!("{:06}.bin", index)), ct)?;
        }
        fs::write(dir.join("meta.json"), serde_json::to_vec(&meta)?)?;
        self.prune(scope, header.version);
        Ok(())
    }

    pub fn latest(&self, scope: u64) -> Option<SnapshotHeader> {
        let mut versions: Vec<i64> = fs::read_dir(self.scope_dir(scope))
            .ok()?
            .flatten()
            .filter_map(|entry| entry.file_name().to_string_lossy().parse::<i64>().ok())
            .collect();
        versions.sort_unstable();
        let version = *versions.last()?;
        self.header(scope, version)
    }

    /// Highest version across all scopes. Only used for informational fields
    /// such as `HELLO_OK.host_lib_version`.
    pub fn max_version(&self) -> i64 {
        let Ok(scopes) = fs::read_dir(&self.root) else {
            return 0;
        };
        let mut max = 0;
        for scope in scopes.flatten() {
            let Ok(versions) = fs::read_dir(scope.path()) else {
                continue;
            };
            for version in versions.flatten() {
                if let Some(value) = version.file_name().to_string_lossy().parse::<i64>().ok() {
                    max = max.max(value);
                }
            }
        }
        max
    }

    pub fn header(&self, scope: u64, version: i64) -> Option<SnapshotHeader> {
        let meta: SnapshotMeta =
            serde_json::from_slice(&fs::read(self.version_dir(scope, version).join("meta.json")).ok()?)
                .ok()?;
        Some(SnapshotHeader {
            version: meta.version,
            key_id: meta.key_id,
            plain_len: meta.plain_len,
            gz_len: meta.gz_len,
            chunks: meta.chunks,
            salt: meta.salt,
        })
    }

    pub fn chunk(&self, scope: u64, version: i64, index: u32) -> Option<Vec<u8>> {
        fs::read(self.version_dir(scope, version).join(format!("{index:06}.bin"))).ok()
    }

    pub fn all_chunks(&self, scope: u64, version: i64) -> Option<Vec<Vec<u8>>> {
        let header = self.header(scope, version)?;
        let mut out = Vec::with_capacity(header.chunks as usize);
        for index in 0..header.chunks {
            out.push(self.chunk(scope, version, index)?);
        }
        Some(out)
    }

    fn prune(&self, scope: u64, keep: i64) {
        let root = self.scope_dir(scope);
        let Ok(entries) = fs::read_dir(&root) else {
            return;
        };
        let mut versions: Vec<i64> = entries
            .flatten()
            .filter_map(|entry| entry.file_name().to_string_lossy().parse::<i64>().ok())
            .collect();
        versions.sort_unstable();
        let keep_from = versions
            .iter()
            .position(|v| *v == keep)
            .map(|pos| pos.saturating_sub(1))
            .unwrap_or(0);
        for version in versions.iter().take(keep_from) {
            let _ = fs::remove_dir_all(self.version_dir(scope, *version));
        }
    }

    /// Removes every stored snapshot (media key changed / host reset).
    pub fn clear_all(&self) {
        if let Ok(entries) = fs::read_dir(&self.root) {
            for entry in entries.flatten() {
                if entry.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                    let _ = fs::remove_dir_all(entry.path());
                }
            }
        }
        let _ = fs::create_dir_all(&self.root);
    }

    pub fn disk_usage(&self) -> u64 {
        fn walk(dir: &Path, total: &mut u64) {
            if let Ok(entries) = fs::read_dir(dir) {
                for entry in entries.flatten() {
                    if let Ok(meta) = entry.metadata() {
                        if meta.is_dir() {
                            walk(&entry.path(), total);
                        } else {
                            *total += meta.len();
                        }
                    }
                }
            }
        }
        let mut total = 0;
        walk(&self.root, &mut total);
        total
    }
}
