use lofty::file::{AudioFile, TaggedFileExt};
use lofty::prelude::Accessor;
use lofty::tag::ItemKey;
use std::io::Read;
use std::path::Path;

#[derive(Debug, Default, Clone)]
pub struct AudioMeta {
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub album_artist: Option<String>,
    pub genre: Option<String>,
    pub year: i64,
    pub track_no: i64,
    pub disc_no: i64,
    pub duration_sec: i64,
    pub picture: Option<(String, Vec<u8>)>,
}

fn clean(value: Option<std::borrow::Cow<'_, str>>) -> Option<String> {
    value
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

fn sniff_image_mime(data: &[u8]) -> &'static str {
    if data.starts_with(&[0x89, b'P', b'N', b'G']) {
        "image/png"
    } else if data.starts_with(&[0xff, 0xd8]) {
        "image/jpeg"
    } else if data.len() > 12 && &data[0..4] == b"RIFF" && &data[8..12] == b"WEBP" {
        "image/webp"
    } else {
        "image/jpeg"
    }
}

/// Reads ID3 / Vorbis / MP4 tags. Returns `None` when the file is not readable
/// as audio by lofty (e.g. most video containers).
pub fn read_audio(path: &Path) -> Option<AudioMeta> {
    let tagged = lofty::read_from_path(path).ok()?;
    let duration_sec = tagged.properties().duration().as_secs() as i64;

    let mut meta = AudioMeta {
        duration_sec,
        ..Default::default()
    };
    let tag = tagged.primary_tag().or_else(|| tagged.first_tag());
    if let Some(tag) = tag {
        meta.title = clean(tag.title());
        meta.artist = clean(tag.artist());
        meta.album = clean(tag.album());
        meta.album_artist = clean(
            tag.get_string(ItemKey::AlbumArtist)
                .map(|s| s.to_string().into()),
        );
        meta.genre = clean(tag.genre());
        meta.year = tag
            .get_string(ItemKey::Year)
            .and_then(|value| value.trim().parse::<i64>().ok())
            .unwrap_or(0);
        meta.track_no = tag.track().map(|t| t as i64).unwrap_or(0);
        meta.disc_no = tag.disk().map(|d| d as i64).unwrap_or(0);
        if let Some(picture) = tag.pictures().first() {
            let data = picture.data();
            if !data.is_empty() && data.len() <= 12 * 1024 * 1024 {
                meta.picture = Some((sniff_image_mime(data).to_string(), data.to_vec()));
            }
        }
    }
    Some(meta)
}

#[derive(Debug, Default, Clone)]
pub struct BookMeta {
    pub title: Option<String>,
    pub author: Option<String>,
    pub cover: Option<(String, Vec<u8>)>,
}

fn decode_entities(input: &str) -> String {
    input
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
}

fn tag_text(xml: &str, tag: &str) -> Option<String> {
    let open = format!("<{tag}");
    let start = xml.find(&open)?;
    let after_open = xml[start..].find('>')? + start + 1;
    let close = format!("</{tag}>");
    let end = xml[after_open..].find(&close)? + after_open;
    let text = decode_entities(xml[after_open..end].trim());
    if text.is_empty() {
        None
    } else {
        Some(text)
    }
}

fn attr_value(xml: &str, attr: &str) -> Option<String> {
    let needle = format!("{attr}=\"");
    let start = xml.find(&needle)? + needle.len();
    let end = xml[start..].find('"')? + start;
    Some(xml[start..end].to_string())
}

fn read_zip_entry(archive: &mut zip::ZipArchive<std::fs::File>, name: &str) -> Option<Vec<u8>> {
    let mut file = archive.by_name(name).ok()?;
    let mut buf = Vec::new();
    file.read_to_end(&mut buf).ok()?;
    Some(buf)
}

/// Minimal EPUB parser: title, author and cover image from the OPF package.
pub fn read_epub(path: &Path) -> BookMeta {
    let mut meta = BookMeta::default();
    let file = match std::fs::File::open(path) {
        Ok(f) => f,
        Err(_) => return meta,
    };
    let mut archive = match zip::ZipArchive::new(file) {
        Ok(a) => a,
        Err(_) => return meta,
    };

    let container = match read_zip_entry(&mut archive, "META-INF/container.xml") {
        Some(c) => String::from_utf8_lossy(&c).to_string(),
        None => return meta,
    };
    let opf_path = match attr_value(&container, "full-path") {
        Some(p) => p,
        None => return meta,
    };
    let opf = match read_zip_entry(&mut archive, &opf_path) {
        Some(o) => String::from_utf8_lossy(&o).to_string(),
        None => return meta,
    };

    meta.title = tag_text(&opf, "dc:title").or_else(|| tag_text(&opf, "title"));
    meta.author = tag_text(&opf, "dc:creator").or_else(|| tag_text(&opf, "creator"));

    // Cover: <item properties="cover-image" href="..."/> or <meta name="cover" content="id"/>
    let mut cover_href: Option<String> = None;
    let mut cover_id: Option<String> = None;
    let mut rest = opf.as_str();
    while let Some(pos) = rest.find("<item ") {
        rest = &rest[pos + 6..];
        let end = match rest.find('>') {
            Some(e) => e,
            None => break,
        };
        let item = &rest[..end];
        if item.contains("cover-image") {
            cover_href = attr_value(item, "href");
            if cover_href.is_some() {
                break;
            }
        }
        if cover_id.is_none() {
            if let (Some(id), Some(href)) = (attr_value(item, "id"), attr_value(item, "href")) {
                if id.to_lowercase().contains("cover") {
                    cover_id = Some(href);
                }
            }
        }
    }
    if cover_href.is_none() {
        cover_href = cover_id;
    }
    if cover_href.is_none() {
        let needle = "name=\"cover\"";
        if let Some(pos) = opf.find(needle) {
            let fragment = &opf[pos..];
            if let Some(content) = attr_value(fragment, "content") {
                let id_needle = format!("id=\"{content}\"");
                if let Some(p) = opf.find(&id_needle) {
                    let start = opf[..p].rfind("<item ").unwrap_or(p);
                    let end = opf[p..].find('>').map(|e| p + e).unwrap_or(p);
                    cover_href = attr_value(&opf[start..end], "href");
                }
            }
        }
    }

    if let Some(href) = cover_href {
        let decoded = decode_entities(&href);
        let opf_dir = Path::new(&opf_path)
            .parent()
            .map(|p| p.to_path_buf())
            .unwrap_or_default();
        let joined = opf_dir.join(decoded);
        let entry = joined.to_string_lossy().replace('\\', "/");
        let entry = entry.trim_start_matches('/').to_string();
        if let Some(data) = read_zip_entry(&mut archive, &entry) {
            if !data.is_empty() && data.len() <= 12 * 1024 * 1024 {
                meta.cover = Some((sniff_image_mime(&data).to_string(), data));
            }
        }
    }

    meta
}
