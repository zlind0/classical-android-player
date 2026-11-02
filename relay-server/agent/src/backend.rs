use anyhow::Result;

/// A media stat as the relay and clients see it.
#[derive(Debug, Clone)]
pub struct Stat {
    pub total: u64,
    pub mime: String,
}

#[derive(Debug, Clone)]
pub struct LiveRequest {
    pub method: String,
    pub path: String,
    pub body: Vec<u8>,
    /// Device that asked for this live request. `None` only for local callers.
    pub client_id: Option<[u8; 16]>,
}

#[derive(Debug, Clone)]
pub struct LiveResponse {
    pub status: u16,
    pub body: Vec<u8>,
}

/// Everything the agent needs from the host application. `path` values are
/// route paths such as `/media/music?id=42` produced by the agent's own index,
/// so they always match what `GET /library/all` advertised.
#[async_trait::async_trait]
pub trait LibraryBackend: Send + Sync {
    async fn library_version(&self) -> Result<i64>;
    async fn snapshot_json(&self) -> Result<Vec<u8>>;
    /// Compact `{music, ebooks, audiobooks}` list used only to build the relay's
    /// file-id index and folder ACL map. Hosts that keep large lazy-scanned
    /// ebook roots out of `/library/all` must override this so those books can
    /// still be downloaded through the relay. Defaults to the full snapshot.
    async fn index_json(&self) -> Result<Vec<u8>> {
        self.snapshot_json().await
    }
    async fn stat(&self, path: &str) -> Result<Stat>;
    async fn fetch_range(&self, path: &str, start: u64, end: u64) -> Result<Vec<u8>>;
    async fn live(&self, req: LiveRequest) -> Result<LiveResponse>;
}
