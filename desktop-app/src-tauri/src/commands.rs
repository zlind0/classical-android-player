use crate::db;
use crate::models::{
    AudiobookDto, BookDto, LibraryStats, MusicLibrary, RootInfo, ScanProgress, ScanSummary,
};
use crate::scanner;
use crate::AppState;
use serde_json::json;
use tauri::{AppHandle, Emitter, State};

type CmdResult<T> = Result<T, String>;

async fn blocking<T, F>(f: F) -> CmdResult<T>
where
    T: Send + 'static,
    F: FnOnce() -> anyhow::Result<T> + Send + 'static,
{
    match tauri::async_runtime::spawn_blocking(f).await {
        Ok(Ok(value)) => Ok(value),
        Ok(Err(err)) => Err(err.to_string()),
        Err(err) => Err(err.to_string()),
    }
}

#[tauri::command]
pub async fn list_roots(state: State<'_, AppState>) -> CmdResult<Vec<RootInfo>> {
    let db = state.db.clone();
    blocking(move || {
        let conn = db.lock();
        db::list_roots(&conn)
    })
    .await
}

#[tauri::command]
pub async fn add_root(
    state: State<'_, AppState>,
    path: String,
    kind: String,
    music_mode: Option<String>,
    display_name: Option<String>,
    fast_scan: Option<bool>,
) -> CmdResult<RootInfo> {
    let db = state.db.clone();
    blocking(move || {
        let conn = db.lock();
        db::add_root(
            &conn,
            &path,
            &kind,
            music_mode.as_deref(),
            display_name.as_deref(),
            fast_scan.unwrap_or(false),
        )
    })
    .await
}

#[tauri::command]
pub async fn update_root(
    state: State<'_, AppState>,
    id: i64,
    display_name: Option<String>,
    music_mode: Option<String>,
    fast_scan: Option<bool>,
) -> CmdResult<RootInfo> {
    let db = state.db.clone();
    blocking(move || {
        let conn = db.lock();
        db::update_root(
            &conn,
            id,
            display_name.as_deref(),
            music_mode.as_deref(),
            fast_scan,
        )
    })
    .await
}

#[tauri::command]
pub async fn remove_root(state: State<'_, AppState>, id: i64) -> CmdResult<()> {
    let db = state.db.clone();
    blocking(move || {
        let conn = db.lock();
        db::remove_root(&conn, id)
    })
    .await
}

#[tauri::command]
pub async fn scan_roots(
    app: AppHandle,
    state: State<'_, AppState>,
    ids: Vec<i64>,
) -> CmdResult<Vec<ScanSummary>> {
    let db = state.db.clone();
    let scan_lock = state.scan_lock.clone();
    let result =
        tauri::async_runtime::spawn_blocking(move || -> anyhow::Result<Vec<ScanSummary>> {
            let _guard = scan_lock
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            let mut summaries = Vec::new();
            for id in ids {
                let handle = app.clone();
                let summary = scanner::scan_root(&db, id, move |progress: ScanProgress| {
                    let _ = handle.emit("scan:progress", &progress);
                })?;
                let _ = app.emit("scan:done", &summary);
                summaries.push(summary);
            }
            Ok(summaries)
        })
        .await;

    match result {
        Ok(Ok(value)) => Ok(value),
        Ok(Err(err)) => Err(err.to_string()),
        Err(err) => Err(err.to_string()),
    }
}

#[tauri::command]
pub async fn music_library(state: State<'_, AppState>) -> CmdResult<MusicLibrary> {
    let db = state.db.clone();
    blocking(move || {
        let conn = db.lock();
        db::music_library(&conn)
    })
    .await
}

#[tauri::command]
pub async fn list_audiobooks(state: State<'_, AppState>) -> CmdResult<Vec<AudiobookDto>> {
    let db = state.db.clone();
    blocking(move || {
        let conn = db.lock();
        db::audiobooks(&conn)
    })
    .await
}

#[tauri::command]
pub async fn list_books(state: State<'_, AppState>) -> CmdResult<Vec<BookDto>> {
    let db = state.db.clone();
    blocking(move || {
        let conn = db.lock();
        db::books(&conn)
    })
    .await
}

#[tauri::command]
pub async fn library_stats(state: State<'_, AppState>) -> CmdResult<LibraryStats> {
    let db = state.db.clone();
    blocking(move || {
        let conn = db.lock();
        db::stats(&conn)
    })
    .await
}

#[tauri::command]
pub fn get_server_info(state: State<'_, AppState>) -> serde_json::Value {
    let info = &state.info;
    json!({
        "deviceId": info.device_id.clone(),
        "deviceName": info.device_name.clone(),
        "port": info.port,
        "baseUrl": format!("http://127.0.0.1:{}", info.port),
        "startedAt": info.started_at,
    })
}
