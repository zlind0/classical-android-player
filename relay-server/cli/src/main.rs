mod client;
mod selftest;

use anyhow::{anyhow, Context, Result};
use pod_relay_agent::AgentConfig;
use pod_relay_protocol::crypto::{decrypt_fragment, key_id, media_key, FRAGMENT_BYTES};
use pod_relay_protocol::file_id::file_id;
use pod_relay_protocol::msg::Msg;
use pod_relay_protocol::FileId;
use std::path::{Path, PathBuf};
use std::time::Duration;

use client::ClientLink;

fn usage() {
    println!(
        "PodRelay relay-server\n\n\
         usage:\n  \
         relay-server server --config relay.json\n  \
         relay-server show --config relay.json\n  \
         relay-server gencert --role relay|host|client --cn NAME [--san dns:host|ip:addr ...] --out PATH\n  \
         relay-server keygen [--out PATH]\n  \
         relay-server fingerprint CERT\n  \
         relay-server status --config agent.json\n  \
         relay-server fetch --config agent.json (--kind K --key V | --file-id HEX) [--start N] [--end N] [--out PATH]\n  \
         relay-server selftest\n"
    );
}

fn flag(args: &[String], name: &str) -> Option<String> {
    let pos = args.iter().position(|a| a == name)?;
    args.get(pos + 1).cloned()
}

fn flags(args: &[String], name: &str) -> Vec<String> {
    let mut out = Vec::new();
    for (index, arg) in args.iter().enumerate() {
        if arg == name {
            if let Some(value) = args.get(index + 1) {
                out.push(value.clone());
            }
        }
    }
    out
}

fn positional(args: &[String]) -> Option<String> {
    args.iter()
        .skip(1)
        .find(|arg| !arg.starts_with("--"))
        .cloned()
}

fn main() {
    println!(
        "[pod-relay] relay-server v{} commit {}",
        env!("CARGO_PKG_VERSION"),
        env!("POD_GIT_COMMIT")
    );
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime");
    if let Err(err) = runtime.block_on(run()) {
        eprintln!("[pod-relay] error: {err:#}");
        std::process::exit(1);
    }
}

async fn run() -> Result<()> {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    match argv.first().map(|s| s.as_str()).unwrap_or("") {
        "server" => cmd_server(&argv).await,
        "show" => cmd_show(&argv),
        "gencert" => cmd_gencert(&argv),
        "keygen" => cmd_keygen(&argv),
        "fingerprint" => cmd_fingerprint(&argv),
        "status" => cmd_status(&argv).await,
        "fetch" => cmd_fetch(&argv).await,
        "selftest" => selftest::run().await,
        _ => {
            usage();
            Ok(())
        }
    }
}

async fn cmd_server(args: &[String]) -> Result<()> {
    let config_path = flag(args, "--config").ok_or_else(|| anyhow!("missing --config"))?;
    let config = pod_relay_server::Config::load(Path::new(&config_path))?;
    let relay = pod_relay_server::Relay::bind(config).await?;
    relay.serve().await
}

fn cmd_show(args: &[String]) -> Result<()> {
    let config_path = flag(args, "--config").ok_or_else(|| anyhow!("missing --config"))?;
    let config = pod_relay_server::Config::load(Path::new(&config_path))?;
    let cert = match &config.tls {
        Some(tls) => tls.cert.clone(),
        None => config.store.dir.join("certs").join("relay.crt"),
    };
    let fingerprint = pod_relay_server::cert::fingerprint_file(&cert)?;
    let auth_text = std::fs::read_to_string(config.store.dir.join("auth.json"))
        .context("read auth.json (start the server once first)")?;
    let auth: serde_json::Value = serde_json::from_str(&auth_text)?;
    let token = auth
        .get("hostToken")
        .and_then(|value| value.as_str())
        .ok_or_else(|| anyhow!("host token missing"))?;
    let host = config
        .public_addr
        .clone()
        .unwrap_or_else(|| config.listen.clone());
    println!(
        "podrelay://relay?v=2&a={}&p={}&t={}",
        url_encode(&host),
        url_encode(&fingerprint),
        url_encode(token)
    );
    Ok(())
}

fn url_encode(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.as_bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*byte as char)
            }
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

fn cmd_gencert(args: &[String]) -> Result<()> {
    let role = flag(args, "--role").unwrap_or_else(|| "relay".to_string());
    let cn = flag(args, "--cn").ok_or_else(|| anyhow!("missing --cn"))?;
    let out = PathBuf::from(flag(args, "--out").ok_or_else(|| anyhow!("missing --out"))?);
    let sans = flags(args, "--san");
    let crt = pod_relay_server::cert::generate(&role, &cn, &sans, &out)?;
    println!("{}", pod_relay_server::cert::fingerprint_file(&crt)?);
    Ok(())
}

fn cmd_keygen(args: &[String]) -> Result<()> {
    let out = PathBuf::from(flag(args, "--out").unwrap_or_else(|| "media.key".to_string()));
    let (encoded, id) = pod_relay_agent::generate_media_key();
    if let Some(parent) = out.parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent)?;
        }
    }
    std::fs::write(&out, &encoded)?;
    println!("media_key: {encoded}");
    println!("key_id: {id}");
    Ok(())
}

fn cmd_fingerprint(args: &[String]) -> Result<()> {
    let path = positional(args).ok_or_else(|| anyhow!("missing certificate path"))?;
    println!("{}", pod_relay_server::cert::fingerprint_file(Path::new(&path))?);
    Ok(())
}

fn load_agent_config(args: &[String]) -> Result<AgentConfig> {
    let path = flag(args, "--config").ok_or_else(|| anyhow!("missing --config"))?;
    let text = std::fs::read_to_string(&path).context("read agent config")?;
    Ok(serde_json::from_str(&text)?)
}

async fn cmd_status(args: &[String]) -> Result<()> {
    let config = load_agent_config(args)?;
    let mut link = ClientLink::connect(&config).await?;
    match link.call(Msg::AdminStatsReq).await? {
        Msg::AdminStatsOk {
            sessions,
            host_online,
            store_used,
            store_cap,
            hit,
            miss,
            evicted,
            lib_version,
        } => {
            println!(
                "{}",
                serde_json::json!({
                    "sessions": sessions,
                    "hostOnline": host_online,
                    "storeUsed": store_used,
                    "storeCap": store_cap,
                    "hit": hit,
                    "miss": miss,
                    "evicted": evicted,
                    "libVersion": lib_version,
                })
            );
            Ok(())
        }
        other => Err(anyhow!("unexpected admin reply: {other:?}")),
    }
}

fn parse_file_id(text: &str) -> Result<FileId> {
    let clean = text.trim();
    if clean.len() != 32 {
        return Err(anyhow!("file id must be 32 hex chars"));
    }
    let mut out = [0u8; 16];
    for index in 0..16 {
        out[index] = u8::from_str_radix(&clean[index * 2..index * 2 + 2], 16)?;
    }
    Ok(out)
}

async fn cmd_fetch(args: &[String]) -> Result<()> {
    let config = load_agent_config(args)?;
    let master = config.media_key()?;
    let key = media_key(&master);
    let id = match flag(args, "--file-id") {
        Some(text) => parse_file_id(&text)?,
        None => {
            let kind = flag(args, "--kind").ok_or_else(|| anyhow!("missing --kind/--file-id"))?;
            let value = flag(args, "--key").ok_or_else(|| anyhow!("missing --key"))?;
            file_id(&kind, &value)
        }
    };

    let mut link = ClientLink::connect(&config).await?;
    let (total, count) = match link.call(Msg::StatReq { file_id: id }).await? {
        Msg::StatOk { total, count, .. } => (total, count),
        Msg::StatErr { code, .. } => return Err(anyhow!("stat failed with code {code}")),
        other => return Err(anyhow!("unexpected stat reply: {other:?}")),
    };
    let start = flag(args, "--start")
        .map(|v| v.parse::<u64>())
        .transpose()?
        .unwrap_or(0);
    let end = flag(args, "--end")
        .map(|v| v.parse::<u64>())
        .transpose()?
        .unwrap_or(total.saturating_sub(1));
    if start > end || end >= total {
        return Err(anyhow!("range {start}-{end} outside 0..{}", total - 1));
    }

    let first = (start / FRAGMENT_BYTES as u64) as u32;
    let last = (end / FRAGMENT_BYTES as u64) as u32;
    let mut assembled = Vec::with_capacity((end - start + 1) as usize);
    for index in first..=count.min(last + 1) {
        if index > last {
            break;
        }
        let (nonce, ct) = loop {
            match link.call(Msg::FragReq { file_id: id, index }).await? {
                Msg::FragData { nonce, ct, .. } => break (nonce, ct),
                Msg::Pending { retry_ms, .. } => {
                    tokio::time::sleep(Duration::from_millis(retry_ms.max(50) as u64)).await;
                }
                other => return Err(anyhow!("unexpected fragment reply: {other:?}")),
            }
        };
        assembled.extend_from_slice(&decrypt_fragment(&key, &id, index, &nonce, &ct)?);
    }
    let frag_start = first as u64 * FRAGMENT_BYTES as u64;
    let slice_start = (start - frag_start) as usize;
    let slice_end = slice_start + (end - start + 1) as usize;
    if assembled.len() < slice_end {
        return Err(anyhow!("short read from relay"));
    }
    let out = &assembled[slice_start..slice_end];
    match flag(args, "--out") {
        Some(path) => {
            std::fs::write(&path, out)?;
            println!("wrote {} bytes to {}", out.len(), path);
        }
        None => {
            println!("{}", out.len());
        }
    }
    println!("key_id {}", key_id(&master));
    Ok(())
}
