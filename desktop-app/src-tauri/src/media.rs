use crate::util::{path_key, sha1_hex};
use anyhow::{anyhow, Result};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::OnceLock;

pub const DEFAULT_COVER: &[u8] = include_bytes!("../assets/default-cover.png");
pub const DEFAULT_COVER_MIME: &str = "image/png";

fn hidden_command(program: &str) -> Command {
    #[allow(unused_mut)]
    let mut command = Command::new(program);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // CREATE_NO_WINDOW: avoid a flashing console for every ffmpeg call.
        command.creation_flags(0x0800_0000);
    }
    command
}

fn find_tool(name: &str) -> Option<PathBuf> {
    let env_key = format!("{}_PATH", name.to_uppercase());
    if let Ok(value) = std::env::var(&env_key) {
        if !value.trim().is_empty() {
            let candidate = PathBuf::from(value);
            if candidate.exists() {
                return Some(candidate);
            }
        }
    }
    let output = hidden_command(name).arg("-version").output().ok()?;
    if output.status.success() {
        Some(PathBuf::from(name))
    } else {
        None
    }
}

pub fn ffmpeg_path() -> Option<&'static Path> {
    static CACHE: OnceLock<Option<PathBuf>> = OnceLock::new();
    CACHE.get_or_init(|| find_tool("ffmpeg")).as_deref()
}

pub fn ffprobe_path() -> Option<&'static Path> {
    static CACHE: OnceLock<Option<PathBuf>> = OnceLock::new();
    CACHE.get_or_init(|| find_tool("ffprobe")).as_deref()
}

/// Duration for containers lofty cannot parse (mkv, mp4 video, ...).
pub fn probe_duration_sec(path: &Path) -> Option<i64> {
    let probe = ffprobe_path()?;
    let output = hidden_command(&probe.to_string_lossy())
        .args([
            "-v",
            "error",
            "-select_streams",
            "a:0",
            "-show_entries",
            "format=duration",
            "-of",
            "default=nw=1:nk=1",
        ])
        .arg(path)
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&output.stdout);
    text.trim()
        .parse::<f64>()
        .ok()
        .map(|v| v.round() as i64)
        .filter(|v| *v > 0)
}

/// Videos are only shared as audio. This remuxes/transcodes the first audio
/// stream into a cached AAC/m4a file so clients never download video data.
/// Without ffmpeg the original file is served as a fallback (audio still plays).
pub fn ensure_audio_source(
    source: &Path,
    from_video: bool,
    cache_dir: &Path,
    mtime: i64,
) -> Result<PathBuf> {
    if !from_video {
        return Ok(source.to_path_buf());
    }
    let Some(ffmpeg) = ffmpeg_path() else {
        return Ok(source.to_path_buf());
    };

    let cache_key = sha1_hex(
        format!(
            "{}\u{1}{}\u{1}{}",
            source.to_string_lossy(),
            mtime,
            path_key(source)
        )
        .as_bytes(),
    );
    let out = cache_dir.join("audio").join(format!("{cache_key}.m4a"));
    if let Ok(meta) = std::fs::metadata(&out) {
        if meta.is_file() && meta.len() > 0 {
            return Ok(out);
        }
    }
    if let Some(parent) = out.parent() {
        std::fs::create_dir_all(parent)?;
    }

    let tmp = out.with_extension(format!("{}.part", std::process::id()));
    let status = hidden_command(&ffmpeg.to_string_lossy())
        .args(["-nostdin", "-v", "error", "-y", "-i"])
        .arg(source)
        .args([
            "-vn",
            "-sn",
            "-dn",
            "-map",
            "0:a:0",
            "-c:a",
            "aac",
            "-b:a",
            "192k",
            "-movflags",
            "+faststart",
        ])
        .arg(&tmp)
        .status();

    match status {
        Ok(status) if status.success() => {
            if out.exists() {
                let _ = std::fs::remove_file(&tmp);
            } else {
                match std::fs::rename(&tmp, &out) {
                    Ok(()) => {}
                    Err(_) if out.exists() => {
                        let _ = std::fs::remove_file(&tmp);
                    }
                    Err(err) => {
                        let _ = std::fs::remove_file(&tmp);
                        return Err(anyhow!("无法写入音频缓存: {err}"));
                    }
                }
            }
            Ok(out)
        }
        Ok(status) => {
            let _ = std::fs::remove_file(&tmp);
            Err(anyhow!("ffmpeg 提取音轨失败: {status}"))
        }
        Err(err) => {
            let _ = std::fs::remove_file(&tmp);
            Err(anyhow!("无法启动 ffmpeg: {err}"))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_cover_is_png() {
        assert!(DEFAULT_COVER.starts_with(&[0x89, b'P', b'N', b'G']));
    }
}
