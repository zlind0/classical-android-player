use crate::db::{self, Db};
use crate::media;
use crate::meta;
use crate::models::{now_ms, ServerInfo};
use crate::util;
use axum::body::Body;
use axum::extract::{Query, State};
use axum::http::{header, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::Router;
use rusqlite::Connection;
use serde_json::json;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncSeekExt};
use tokio_util::io::ReaderStream;
use tower_http::cors::{Any, CorsLayer};

#[derive(Clone)]
pub struct HttpState {
    pub db: Arc<Db>,
    pub info: Arc<ServerInfo>,
    pub cache_dir: PathBuf,
}

pub fn build_router(state: HttpState) -> Router {
    Router::new()
        .route("/", get(index))
        .route("/library/version", get(library_version))
        .route("/library/all", get(library_all))
        .route("/library/ebook/scan", get(library_ebook_scan))
        .route("/media/music", get(media_music))
        .route("/media/audiobook", get(media_audiobook))
        .route("/media/ebook", get(media_ebook))
        .route("/media/songart", get(media_songart))
        .route("/media/cover", get(media_cover))
        .fallback(not_found)
        .layer(
            CorsLayer::new()
                .allow_origin(Any)
                .allow_methods(Any)
                .allow_headers(Any),
        )
        .with_state(state)
}

async fn index(State(state): State<HttpState>) -> Response {
    let body = format!(
        "Aurora Desktop · {} ({})\n\nGET /library/version\nGET /library/all\nGET /media/music?id=\nGET /media/audiobook?path=\nGET /media/ebook?path=\nGET /media/songart?id=\nGET /media/cover?kind=ebook|audiobook&key=\n",
        state.info.device_name, state.info.device_id
    );
    text_response(StatusCode::OK, body, "text/plain; charset=utf-8")
}

fn text_response(status: StatusCode, body: String, mime: &str) -> Response {
    (status, [(header::CONTENT_TYPE, mime.to_string())], body).into_response()
}

fn json_response(value: serde_json::Value) -> Response {
    match serde_json::to_vec(&value) {
        Ok(bytes) => {
            let mut response = Response::new(Body::from(bytes));
            let headers = response.headers_mut();
            headers.insert(
                header::CONTENT_TYPE,
                HeaderValue::from_static("application/json; charset=utf-8"),
            );
            headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
            response
        }
        Err(err) => text_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("serialize error: {err}"),
            "text/plain; charset=utf-8",
        ),
    }
}

async fn not_found() -> Response {
    text_response(
        StatusCode::NOT_FOUND,
        "not found".to_string(),
        "text/plain; charset=utf-8",
    )
}

async fn run_db<T, F>(state: &HttpState, f: F) -> Result<T, anyhow::Error>
where
    T: Send + 'static,
    F: FnOnce(&Connection) -> anyhow::Result<T> + Send + 'static,
{
    let db = state.db.clone();
    tokio::task::spawn_blocking(move || {
        let conn = db.lock();
        f(&conn)
    })
    .await
    .map_err(|err| anyhow::anyhow!("database task failed: {err}"))?
}

async fn library_version(State(state): State<HttpState>) -> Response {
    match run_db(&state, db::library_version).await {
        Ok(version) => json_response(json!({ "version": version })),
        Err(err) => text_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            err.to_string(),
            "text/plain; charset=utf-8",
        ),
    }
}

/// Lazily parses and caches metadata for one fast-scanned book.
/// Clients call this only for books that were actually randomly selected.
async fn library_ebook_scan(
    State(state): State<HttpState>,
    Query(query): Query<HashMap<String, String>>,
) -> Response {
    let Some(path) = query.get("path").cloned() else {
        return text_response(
            StatusCode::BAD_REQUEST,
            "missing path".into(),
            "text/plain; charset=utf-8",
        );
    };
    let lookup_path = path.clone();
    let info = match run_db(&state, move |conn| db::book_scan_info(conn, &lookup_path)).await {
        Ok(Some(info)) => info,
        Ok(None) => {
            return text_response(
                StatusCode::NOT_FOUND,
                "unknown book".into(),
                "text/plain; charset=utf-8",
            )
        }
        Err(err) => {
            return text_response(
                StatusCode::INTERNAL_SERVER_ERROR,
                err.to_string(),
                "text/plain; charset=utf-8",
            )
        }
    };

    // Already parsed once: serve the cached result directly.
    if info.meta_scanned {
        return json_response(json!({
            "path": path,
            "title": info.title,
            "author": info.author,
            "hasCover": info.has_cover,
        }));
    }

    let source_path = info.path;
    let ext = info.format;
    let parsed = tokio::task::spawn_blocking(move || {
        if ext == "epub" {
            meta::read_epub(&source_path)
        } else {
            meta::BookMeta::default()
        }
    })
    .await;
    let parsed = match parsed {
        Ok(meta) => meta,
        Err(err) => {
            return text_response(
                StatusCode::INTERNAL_SERVER_ERROR,
                err.to_string(),
                "text/plain; charset=utf-8",
            )
        }
    };

    let apply_path = path.clone();
    let updated = run_db(&state, move |conn| {
        db::apply_book_meta(conn, &apply_path, &parsed)
    })
    .await;
    match updated {
        Ok((title, author, has_cover)) => json_response(json!({
            "path": path,
            "title": title,
            "author": author,
            "hasCover": has_cover,
        })),
        Err(err) => text_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            err.to_string(),
            "text/plain; charset=utf-8",
        ),
    }
}

async fn library_all(State(state): State<HttpState>) -> Response {
    let device_id = state.info.device_id.clone();
    let device_name = state.info.device_name.clone();
    let result = run_db(&state, move |conn| {
        db::wire_snapshot(conn, &device_id, &device_name)
    })
    .await;

    match result {
        Ok(snapshot) => match serde_json::to_vec(&snapshot) {
            Ok(bytes) => {
                let mut response = Response::new(Body::from(bytes));
                response.headers_mut().insert(
                    header::CONTENT_TYPE,
                    HeaderValue::from_static("application/json; charset=utf-8"),
                );
                response
                    .headers_mut()
                    .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
                response
            }
            Err(err) => text_response(
                StatusCode::INTERNAL_SERVER_ERROR,
                err.to_string(),
                "text/plain; charset=utf-8",
            ),
        },
        Err(err) => text_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            err.to_string(),
            "text/plain; charset=utf-8",
        ),
    }
}

fn mime_for_path(path: &Path) -> String {
    path.extension()
        .and_then(|e| e.to_str())
        .map(|e| util::audio_mime(&e.to_ascii_lowercase()).to_string())
        .unwrap_or_else(|| "application/octet-stream".to_string())
}

async fn media_music(
    State(state): State<HttpState>,
    Query(query): Query<HashMap<String, String>>,
    headers: axum::http::HeaderMap,
) -> Response {
    let Some(id) = query.get("id") else {
        return text_response(
            StatusCode::BAD_REQUEST,
            "missing id".into(),
            "text/plain; charset=utf-8",
        );
    };
    let id = id.clone();
    let source = match run_db(&state, move |conn| db::track_source(conn, &id)).await {
        Ok(Some(source)) => source,
        Ok(None) => {
            return text_response(
                StatusCode::NOT_FOUND,
                "unknown track".into(),
                "text/plain; charset=utf-8",
            )
        }
        Err(err) => {
            return text_response(
                StatusCode::INTERNAL_SERVER_ERROR,
                err.to_string(),
                "text/plain; charset=utf-8",
            )
        }
    };
    serve_media_source(&state, source, headers).await
}

async fn media_audiobook(
    State(state): State<HttpState>,
    Query(query): Query<HashMap<String, String>>,
    headers: axum::http::HeaderMap,
) -> Response {
    let Some(path) = query.get("path") else {
        return text_response(
            StatusCode::BAD_REQUEST,
            "missing path".into(),
            "text/plain; charset=utf-8",
        );
    };
    let path = path.clone();
    let source = match run_db(&state, move |conn| db::chapter_source(conn, &path)).await {
        Ok(Some(source)) => source,
        Ok(None) => {
            return text_response(
                StatusCode::NOT_FOUND,
                "unknown chapter".into(),
                "text/plain; charset=utf-8",
            )
        }
        Err(err) => {
            return text_response(
                StatusCode::INTERNAL_SERVER_ERROR,
                err.to_string(),
                "text/plain; charset=utf-8",
            )
        }
    };
    serve_media_source(&state, source, headers).await
}

async fn serve_media_source(
    state: &HttpState,
    source: db::MediaSource,
    headers: axum::http::HeaderMap,
) -> Response {
    let cache_dir = state.cache_dir.clone();
    let path = source.path.clone();
    let from_video = source.from_video;
    let mtime = std::fs::metadata(&path)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);

    let resolved = tokio::task::spawn_blocking(move || {
        media::ensure_audio_source(&path, from_video, &cache_dir, mtime)
    })
    .await;

    let resolved = match resolved {
        Ok(Ok(path)) => path,
        Ok(Err(err)) => {
            return text_response(
                StatusCode::INTERNAL_SERVER_ERROR,
                err.to_string(),
                "text/plain; charset=utf-8",
            )
        }
        Err(err) => {
            return text_response(
                StatusCode::INTERNAL_SERVER_ERROR,
                err.to_string(),
                "text/plain; charset=utf-8",
            )
        }
    };

    let mime = mime_for_path(&resolved);
    let range = headers
        .get(header::RANGE)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string());
    serve_file(&resolved, &mime, range).await
}

async fn media_ebook(
    State(state): State<HttpState>,
    Query(query): Query<HashMap<String, String>>,
    headers: axum::http::HeaderMap,
) -> Response {
    let Some(path) = query.get("path") else {
        return text_response(
            StatusCode::BAD_REQUEST,
            "missing path".into(),
            "text/plain; charset=utf-8",
        );
    };
    let path = path.clone();
    let found = match run_db(&state, move |conn| db::book_source(conn, &path)).await {
        Ok(Some(found)) => found,
        Ok(None) => {
            return text_response(
                StatusCode::NOT_FOUND,
                "unknown book".into(),
                "text/plain; charset=utf-8",
            )
        }
        Err(err) => {
            return text_response(
                StatusCode::INTERNAL_SERVER_ERROR,
                err.to_string(),
                "text/plain; charset=utf-8",
            )
        }
    };
    let mime = util::ebook_mime(&found.1).to_string();
    let range = headers
        .get(header::RANGE)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string());
    serve_file(&found.0, &mime, range).await
}

async fn media_songart(
    State(state): State<HttpState>,
    Query(query): Query<HashMap<String, String>>,
) -> Response {
    let Some(id) = query.get("id") else {
        return text_response(
            StatusCode::BAD_REQUEST,
            "missing id".into(),
            "text/plain; charset=utf-8",
        );
    };
    let id = id.clone();
    let art = run_db(&state, move |conn| db::track_art(conn, &id)).await;
    match art {
        Ok(Some((mime, data))) => bytes_response(&mime, data),
        _ => bytes_response(media::DEFAULT_COVER_MIME, media::DEFAULT_COVER.to_vec()),
    }
}

async fn media_cover(
    State(state): State<HttpState>,
    Query(query): Query<HashMap<String, String>>,
) -> Response {
    let kind = query.get("kind").cloned().unwrap_or_default();
    let key = query.get("key").cloned().unwrap_or_default();
    if key.is_empty() {
        return bytes_response(media::DEFAULT_COVER_MIME, media::DEFAULT_COVER.to_vec());
    }
    let art = run_db(&state, move |conn| match kind.as_str() {
        "ebook" => db::book_art(conn, &key),
        "audiobook" => db::audiobook_art(conn, &key),
        _ => Ok(None),
    })
    .await;
    match art {
        Ok(Some((mime, data))) => bytes_response(&mime, data),
        _ => bytes_response(media::DEFAULT_COVER_MIME, media::DEFAULT_COVER.to_vec()),
    }
}

fn bytes_response(mime: &str, data: Vec<u8>) -> Response {
    let len = data.len();
    let mut response = Response::new(Body::from(data));
    let headers = response.headers_mut();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_str(mime).unwrap_or(HeaderValue::from_static("application/octet-stream")),
    );
    headers.insert(
        header::CONTENT_LENGTH,
        HeaderValue::from_str(&len.to_string()).unwrap(),
    );
    headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

enum RangeSpec {
    Full,
    Partial(u64, u64),
    Invalid,
}

fn parse_range(header_value: Option<&str>, total: u64) -> RangeSpec {
    let Some(value) = header_value else {
        return RangeSpec::Full;
    };
    let Some(spec) = value.trim().strip_prefix("bytes=") else {
        return RangeSpec::Invalid;
    };
    let first = spec.split(',').next().unwrap_or("").trim();
    let Some((raw_start, raw_end)) = first.split_once('-') else {
        return RangeSpec::Invalid;
    };
    if total == 0 {
        return RangeSpec::Invalid;
    }

    if raw_start.is_empty() {
        let Ok(suffix) = raw_end.trim().parse::<u64>() else {
            return RangeSpec::Invalid;
        };
        if suffix == 0 {
            return RangeSpec::Invalid;
        }
        let start = total.saturating_sub(suffix);
        return RangeSpec::Partial(start, total - 1);
    }

    let Ok(start) = raw_start.trim().parse::<u64>() else {
        return RangeSpec::Invalid;
    };
    if start >= total {
        return RangeSpec::Invalid;
    }
    let end = if raw_end.trim().is_empty() {
        total - 1
    } else {
        match raw_end.trim().parse::<u64>() {
            Ok(end) => end.min(total - 1),
            Err(_) => return RangeSpec::Invalid,
        }
    };
    if end < start {
        return RangeSpec::Invalid;
    }
    RangeSpec::Partial(start, end)
}

async fn serve_file(path: &Path, mime: &str, range: Option<String>) -> Response {
    let mut file = match tokio::fs::File::open(path).await {
        Ok(file) => file,
        Err(err) => {
            return text_response(
                StatusCode::NOT_FOUND,
                format!("file unavailable: {err}"),
                "text/plain; charset=utf-8",
            )
        }
    };
    let total = match file.metadata().await {
        Ok(meta) => meta.len(),
        Err(err) => {
            return text_response(
                StatusCode::INTERNAL_SERVER_ERROR,
                err.to_string(),
                "text/plain; charset=utf-8",
            )
        }
    };

    let spec = parse_range(range.as_deref(), total);
    match spec {
        RangeSpec::Invalid => {
            let mut response = text_response(
                StatusCode::RANGE_NOT_SATISFIABLE,
                "range not satisfiable".to_string(),
                "text/plain; charset=utf-8",
            );
            response.headers_mut().insert(
                header::CONTENT_RANGE,
                HeaderValue::from_str(&format!("bytes */{total}"))
                    .unwrap_or(HeaderValue::from_static("bytes */0")),
            );
            response
        }
        RangeSpec::Full => {
            let stream = ReaderStream::new(file);
            let mut response = Response::new(Body::from_stream(stream));
            let headers = response.headers_mut();
            headers.insert(
                header::CONTENT_TYPE,
                HeaderValue::from_str(mime)
                    .unwrap_or(HeaderValue::from_static("application/octet-stream")),
            );
            headers.insert(
                header::CONTENT_LENGTH,
                HeaderValue::from_str(&total.to_string()).unwrap(),
            );
            headers.insert(header::ACCEPT_RANGES, HeaderValue::from_static("bytes"));
            headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
            response
        }
        RangeSpec::Partial(start, end) => {
            if let Err(err) = file.seek(std::io::SeekFrom::Start(start)).await {
                return text_response(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    err.to_string(),
                    "text/plain; charset=utf-8",
                );
            }
            let length = end - start + 1;
            let stream = ReaderStream::new(file.take(length));
            let mut response = Response::new(Body::from_stream(stream));
            let response_headers = response.headers_mut();
            response_headers.insert(
                header::CONTENT_TYPE,
                HeaderValue::from_str(mime)
                    .unwrap_or(HeaderValue::from_static("application/octet-stream")),
            );
            response_headers.insert(
                header::CONTENT_LENGTH,
                HeaderValue::from_str(&length.to_string()).unwrap(),
            );
            response_headers.insert(header::ACCEPT_RANGES, HeaderValue::from_static("bytes"));
            response_headers.insert(
                header::CONTENT_RANGE,
                HeaderValue::from_str(&format!("bytes {start}-{end}/{total}"))
                    .unwrap_or_else(|_| HeaderValue::from_static("bytes */0")),
            );
            response_headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
            *response.status_mut() = StatusCode::PARTIAL_CONTENT;
            response
        }
    }
}

pub fn pick_port_and_listener() -> std::io::Result<(std::net::TcpListener, u16)> {
    for port in 8080u16..=8099 {
        if let Ok(listener) = std::net::TcpListener::bind(("0.0.0.0", port)) {
            listener.set_nonblocking(true)?;
            return Ok((listener, port));
        }
    }
    let listener = std::net::TcpListener::bind(("0.0.0.0", 0))?;
    let port = listener.local_addr()?.port();
    listener.set_nonblocking(true)?;
    Ok((listener, port))
}

pub async fn serve(listener: std::net::TcpListener, state: HttpState) {
    let listener = match tokio::net::TcpListener::from_std(listener) {
        Ok(listener) => listener,
        Err(err) => {
            eprintln!("[aurora] failed to start HTTP listener: {err}");
            return;
        }
    };
    let app = build_router(state);
    if let Err(err) = axum::serve(listener, app).await {
        eprintln!("[aurora] HTTP server stopped: {err}");
    }
}

#[allow(dead_code)]
pub fn generated_at() -> i64 {
    now_ms()
}

#[cfg(test)]
mod tests {
    use super::{parse_range, RangeSpec};

    fn partial(spec: RangeSpec) -> Option<(u64, u64)> {
        match spec {
            RangeSpec::Partial(start, end) => Some((start, end)),
            _ => None,
        }
    }

    #[test]
    fn no_header_means_full() {
        assert!(matches!(parse_range(None, 100), RangeSpec::Full));
    }

    #[test]
    fn open_ended_and_suffix_ranges() {
        assert_eq!(partial(parse_range(Some("bytes=10-"), 100)), Some((10, 99)));
        assert_eq!(partial(parse_range(Some("bytes=0-9"), 100)), Some((0, 9)));
        assert_eq!(partial(parse_range(Some("bytes=-20"), 100)), Some((80, 99)));
        assert_eq!(
            partial(parse_range(Some("bytes=50-1000"), 100)),
            Some((50, 99))
        );
    }

    #[test]
    fn invalid_ranges_are_rejected() {
        assert!(matches!(
            parse_range(Some("bytes=200-"), 100),
            RangeSpec::Invalid
        ));
        assert!(matches!(
            parse_range(Some("bytes=10-5"), 100),
            RangeSpec::Invalid
        ));
        assert!(matches!(
            parse_range(Some("items=0-1"), 100),
            RangeSpec::Invalid
        ));
        assert!(matches!(
            parse_range(Some("bytes=-0"), 100),
            RangeSpec::Invalid
        ));
    }
}
