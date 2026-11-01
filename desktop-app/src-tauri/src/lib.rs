mod commands;
mod db;
mod discovery;
mod http_server;
mod media;
mod meta;
mod models;
mod relay_backend;
mod relay_host;
mod relay_pairing;
mod scanner;
mod util;

use models::{now_ms, ServerInfo};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use tauri::Manager;

pub struct AppState {
    pub db: Arc<db::Db>,
    pub info: Arc<ServerInfo>,
    pub scan_lock: Arc<Mutex<()>>,
    pub cache_dir: PathBuf,
    pub data_dir: PathBuf,
    pub relay: Arc<relay_host::RelayManager>,
}

fn boxed_error(error: impl ToString) -> Box<dyn std::error::Error> {
    error.to_string().into()
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let data_dir = app.path().app_data_dir().map_err(boxed_error)?;
            std::fs::create_dir_all(&data_dir).map_err(boxed_error)?;
            let cache_dir = data_dir.join("cache");
            std::fs::create_dir_all(&cache_dir).map_err(boxed_error)?;

            let database =
                Arc::new(db::Db::open(&data_dir.join("library.db")).map_err(boxed_error)?);

            let (device_id, device_name) = {
                let conn = database.lock();
                db::ensure_identity(&conn).map_err(boxed_error)?
            };

            {
                let conn = database.lock();
                let _ = db::refresh_library_version(&conn);
            }

            let (listener, port) = http_server::pick_port_and_listener().map_err(boxed_error)?;
            let info = Arc::new(ServerInfo {
                device_id,
                device_name,
                port,
                started_at: now_ms(),
            });

            let (pair_tx, mut pair_rx) = tokio::sync::mpsc::unbounded_channel();
            let pairing = Arc::new(relay_pairing::PairingState::new(pair_tx));

            let http_state = http_server::HttpState {
                db: database.clone(),
                info: info.clone(),
                cache_dir: cache_dir.clone(),
                pairing: pairing.clone(),
            };

            let relay = relay_host::RelayManager::load(
                data_dir.clone(),
                http_state.clone(),
                pairing.clone(),
            );
            let _ = relay.start_if_enabled();
            let relay_for_pairs = relay.clone();
            tauri::async_runtime::spawn(async move {
                while let Some(outcome) = pair_rx.recv().await {
                    relay_for_pairs.authorize_client(outcome);
                }
            });
            eprintln!("[aurora] relay {}", if relay.settings().enabled { "enabled" } else { "disabled" });

            app.manage(AppState {
                db: database.clone(),
                info: info.clone(),
                scan_lock: Arc::new(Mutex::new(())),
                cache_dir,
                data_dir,
                relay,
            });

            let discovery = discovery::start(&info);
            app.manage(discovery);

            eprintln!(
                "[aurora] library host ready on http://0.0.0.0:{} (device {})",
                info.port, info.device_id
            );
            tauri::async_runtime::spawn(http_server::serve(listener, http_state));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::list_roots,
            commands::add_root,
            commands::update_root,
            commands::remove_root,
            commands::scan_roots,
            commands::music_library,
            commands::list_audiobooks,
            commands::list_books,
            commands::library_stats,
            commands::get_server_info,
            commands::relay_status,
            commands::relay_apply_settings,
            commands::relay_apply_bootstrap,
            commands::relay_start_pairing,
            commands::relay_cancel_pairing,
            commands::relay_revoke_device,
            commands::relay_reset_identity,
            commands::relay_reset_encryption,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Aurora Desktop");
}
