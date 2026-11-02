//! Adapter that exposes the existing library router to the relay agent.
//!
//! The agent never opens a port: every stat/range/live call is executed against
//! `build_router()` in-process, so paths, Range semantics, video audio
//! extraction and ebook lazy parsing are all identical to the LAN protocol.

use crate::relay_access::{self, SharedGrants};
use crate::{db, http_server};
use anyhow::{anyhow, Context, Result};
use axum::body::{to_bytes, Body};
use axum::http::{header, HeaderMap, Request, StatusCode};
use axum::Router;
use pod_relay_agent::{LibraryBackend, LiveRequest, LiveResponse, Stat};
use std::sync::Arc;
use tower::ServiceExt;

const MAX_BODY: usize = 32 * 1024 * 1024;

pub struct HostBackend {
    router: Router,
    state: http_server::HttpState,
    grants: SharedGrants,
}

impl HostBackend {
    pub fn new(state: http_server::HttpState, grants: SharedGrants) -> Arc<HostBackend> {
        let router = http_server::build_router(state.clone());
        Arc::new(HostBackend {
            router,
            state,
            grants,
        })
    }

    async fn request(
        &self,
        method: &str,
        uri: &str,
        range: Option<String>,
        body: Vec<u8>,
    ) -> Result<(StatusCode, HeaderMap, Vec<u8>)> {
        let mut builder = Request::builder().method(method).uri(uri);
        if let Some(range) = range {
            builder = builder.header("range", range);
        }
        if !body.is_empty() && method != "GET" {
            builder = builder.header(header::CONTENT_TYPE, "application/json; charset=utf-8");
        }
        let request = builder
            .body(Body::from(body))
            .with_context(|| format!("build request {uri}"))?;
        let response = self
            .router
            .clone()
            .oneshot(request)
            .await
            .map_err(|err| anyhow!("router error: {err}"))?;
        let status = response.status();
        let headers = response.headers().clone();
        let bytes = to_bytes(response.into_body(), MAX_BODY)
            .await
            .map_err(|err| anyhow!("read body: {err}"))?;
        Ok((status, headers, bytes.to_vec()))
    }
}

fn total_from_content_range(value: &str) -> Option<u64> {
    // "bytes 0-0/12345"
    value.rsplit('/').next()?.trim().parse::<u64>().ok()
}

#[async_trait::async_trait]
impl LibraryBackend for HostBackend {
    async fn library_version(&self) -> Result<i64> {
        let db = self.state.db.clone();
        tauri::async_runtime::spawn_blocking(move || {
            let conn = db.lock();
            db::library_version(&conn)
        })
        .await?
    }

    async fn snapshot_json(&self) -> Result<Vec<u8>> {
        let db = self.state.db.clone();
        let device_id = self.state.info.device_id.clone();
        let device_name = self.state.info.device_name.clone();
        tauri::async_runtime::spawn_blocking(move || {
            let conn = db.lock();
            let snapshot = db::wire_snapshot(&conn, &device_id, &device_name)?;
            serde_json::to_vec(&snapshot).context("serialize snapshot")
        })
        .await?
    }

    async fn index_json(&self) -> Result<Vec<u8>> {
        let db = self.state.db.clone();
        tauri::async_runtime::spawn_blocking(move || {
            let conn = db.lock();
            let index = db::wire_index(&conn)?;
            serde_json::to_vec(&index).context("serialize relay index")
        })
        .await?
    }

    async fn stat(&self, path: &str) -> Result<Stat> {
        let (status, headers, body) = self
            .request("GET", path, Some("bytes=0-0".to_string()), Vec::new())
            .await?;
        if !status.is_success() {
            return Err(anyhow!("host returned {status} for {path}"));
        }
        let mime = headers
            .get(header::CONTENT_TYPE)
            .and_then(|value| value.to_str().ok())
            .unwrap_or("application/octet-stream")
            .to_string();
        let total = headers
            .get(header::CONTENT_RANGE)
            .and_then(|value| value.to_str().ok())
            .and_then(total_from_content_range)
            // Non-range endpoints (covers, default art) answer 200 with the
            // whole body, so its length is the total.
            .unwrap_or(body.len() as u64);
        Ok(Stat { total, mime })
    }

    async fn fetch_range(&self, path: &str, start: u64, end: u64) -> Result<Vec<u8>> {
        if end < start {
            return Err(anyhow!("invalid range {start}-{end}"));
        }
        let (status, headers, body) = self
            .request(
                "GET",
                path,
                Some(format!("bytes={start}-{end}")),
                Vec::new(),
            )
            .await?;
        if !status.is_success() {
            return Err(anyhow!("host returned {status} for {path}"));
        }
        let want = (end - start + 1) as usize;
        if body.len() == want {
            return Ok(body);
        }
        if status == StatusCode::OK {
            // The endpoint ignored the Range header; trim locally.
            let begin = start as usize;
            let finish = (end as usize + 1).min(body.len());
            if begin < finish {
                return Ok(body[begin..finish].to_vec());
            }
        }
        let _ = headers;
        Err(anyhow!(
            "host returned {} bytes for range {start}-{end} of {path}",
            body.len()
        ))
    }

    async fn live(&self, req: LiveRequest) -> Result<LiveResponse> {
        let decision = match relay_access::check(
            &self.state,
            &self.grants,
            req.client_id,
            &req.method,
            &req.path,
            &req.body,
        )
        .await
        {
            Ok(decision) => decision,
            Err(denied) => {
                let body = serde_json::json!({ "error": denied.message });
                return Ok(LiveResponse {
                    status: denied.status,
                    body: serde_json::to_vec(&body).unwrap_or_default(),
                });
            }
        };
        let is_feed_start = req.path.starts_with("/library/ebook/feed/start");
        let (status, _headers, body) = self.request(&req.method, &req.path, None, req.body).await?;
        if status.is_success() {
            if is_feed_start {
                relay_access::claim_from_response(&self.grants, req.client_id, &body);
            }
            if let Some(session) = decision.release_feed.as_deref() {
                relay_access::release_session(&self.grants, session);
            }
        }
        Ok(LiveResponse {
            status: status.as_u16(),
            body,
        })
    }
}
