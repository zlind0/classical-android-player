//! Minimal EPUB parser for the random-reading feed.
//!
//! The desktop host never renders books: it only needs a stable chapter/block
//! structure so the phone can paginate locally and map a random `(chapter,
//! block, char)` position onto its own pages.  We therefore deliberately avoid
//! a full HTML/DOM dependency: tags are streamed, block-level tags become
//! paragraph boundaries and headings keep their level.

use anyhow::{anyhow, Context, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::Read;
use std::path::{Component, Path};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeedBlock {
    pub text: String,
    /// 0 = body text, 1..6 = h1..h6.
    pub level: u8,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeedChapter {
    pub title: String,
    pub blocks: Vec<FeedBlock>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ParsedBook {
    pub title: String,
    pub author: String,
    pub chapters: Vec<FeedChapter>,
}

fn read_entry(archive: &mut zip::ZipArchive<std::fs::File>, name: &str) -> Option<Vec<u8>> {
    let mut file = archive.by_name(name).ok()?;
    let mut buf = Vec::new();
    file.read_to_end(&mut buf).ok()?;
    Some(buf)
}

fn attr_value(tag: &str, attr: &str) -> Option<String> {
    for quote in ['"', '\''] {
        let needle = format!("{attr}={quote}");
        if let Some(start) = tag.find(&needle) {
            let from = start + needle.len();
            if let Some(end) = tag[from..].find(quote) {
                return Some(tag[from..from + end].to_string());
            }
        }
    }
    None
}

fn is_heading(name: &str) -> Option<u8> {
    let bytes = name.as_bytes();
    if bytes.len() == 2 && bytes[0] == b'h' && (b'1'..=b'6').contains(&bytes[1]) {
        Some(bytes[1] - b'0')
    } else {
        None
    }
}

fn is_block(name: &str) -> bool {
    matches!(
        name,
        "p" | "div"
            | "li"
            | "blockquote"
            | "br"
            | "tr"
            | "section"
            | "article"
            | "pre"
            | "figure"
            | "figcaption"
            | "dd"
            | "dt"
            | "td"
            | "th"
            | "h1"
            | "h2"
            | "h3"
            | "h4"
            | "h5"
            | "h6"
    )
}

fn decode_entities(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut rest = input;
    while let Some(pos) = rest.find('&') {
        out.push_str(&rest[..pos]);
        rest = &rest[pos..];
        let end = rest.find(';').filter(|e| *e <= 12);
        let Some(end) = end else {
            out.push('&');
            rest = &rest[1..];
            continue;
        };
        let entity = &rest[1..end];
        let decoded: Option<char> = match entity {
            "amp" => Some('&'),
            "lt" => Some('<'),
            "gt" => Some('>'),
            "quot" => Some('"'),
            "apos" => Some('\''),
            "nbsp" => Some(' '),
            "mdash" => Some('—'),
            "ndash" => Some('–'),
            "hellip" => Some('…'),
            "ldquo" => Some('“'),
            "rdquo" => Some('”'),
            "lsquo" => Some('‘'),
            "rsquo" => Some('’'),
            _ => {
                if let Some(hex) = entity.strip_prefix("#x").or_else(|| entity.strip_prefix("#X")) {
                    u32::from_str_radix(hex, 16).ok().and_then(char::from_u32)
                } else if let Some(dec) = entity.strip_prefix('#') {
                    dec.parse::<u32>().ok().and_then(char::from_u32)
                } else {
                    None
                }
            }
        };
        match decoded {
            Some(ch) => {
                out.push(ch);
                rest = &rest[end + 1..];
            }
            None => {
                out.push('&');
                rest = &rest[1..];
            }
        }
    }
    out.push_str(rest);
    out
}

fn collapse_ws(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut space = false;
    for ch in input.chars() {
        if ch.is_whitespace() {
            space = true;
            continue;
        }
        if space && !out.is_empty() {
            out.push(' ');
        }
        space = false;
        out.push(ch);
    }
    out.trim().to_string()
}

fn flush(buf: &mut String, level: u8, out: &mut Vec<FeedBlock>) {
    if buf.is_empty() {
        return;
    }
    let decoded = decode_entities(buf);
    let text = collapse_ws(&decoded);
    buf.clear();
    if !text.is_empty() {
        out.push(FeedBlock { text, level });
    }
}

fn tag_name(raw: &str) -> (bool, String) {
    let trimmed = raw.trim_start();
    let closing = trimmed.starts_with('/');
    let body = trimmed.trim_start_matches('/').trim_start();
    let name: String = body
        .chars()
        .take_while(|c| c.is_ascii_alphanumeric() || *c == ':' || *c == '-' || *c == '_')
        .collect();
    (closing, name.to_ascii_lowercase())
}

/// Converts one XHTML document into flat paragraph/heading blocks.
pub fn html_to_blocks(html: &str) -> Vec<FeedBlock> {
    let mut out = Vec::new();
    let mut buf = String::new();
    let mut level: u8 = 0;
    let mut i = 0usize;
    let bytes = html.as_bytes();
    while i < bytes.len() {
        if bytes[i] == b'<' {
            let end = match html[i..].find('>') {
                Some(offset) => i + offset,
                None => break,
            };
            let raw = &html[i + 1..end];
            let (closing, name) = tag_name(raw);
            if !closing && (name == "script" || name == "style") {
                let close = format!("</{name}");
                if let Some(offset) = html[end + 1..].to_ascii_lowercase().find(&close) {
                    let after = end + 1 + offset;
                    i = match html[after..].find('>') {
                        Some(gt) => after + gt + 1,
                        None => break,
                    };
                } else {
                    break;
                }
                continue;
            }
            if let Some(h) = is_heading(&name) {
                if closing {
                    flush(&mut buf, level, &mut out);
                    level = 0;
                } else {
                    flush(&mut buf, level, &mut out);
                    level = h;
                }
            } else if is_block(&name) {
                // A block boundary always terminates the current paragraph; `<br>`
                // is a block boundary without a matching close tag.
                if closing {
                    flush(&mut buf, level, &mut out);
                    if level != 0 {
                        level = 0;
                    }
                } else {
                    flush(&mut buf, level, &mut out);
                }
            }
            i = end + 1;
        } else {
            let next = html[i..].find('<').map(|p| i + p).unwrap_or(bytes.len());
            buf.push_str(&html[i..next]);
            i = next;
        }
    }
    flush(&mut buf, level, &mut out);
    out
}

fn normalize_path(base_dir: &Path, href: &str) -> String {
    let href = href.split('#').next().unwrap_or(href);
    let joined = base_dir.join(href);
    let mut parts: Vec<String> = Vec::new();
    for component in joined.components() {
        match component {
            Component::ParentDir => {
                parts.pop();
            }
            Component::Normal(value) => parts.push(value.to_string_lossy().to_string()),
            _ => {}
        }
    }
    parts.join("/")
}

fn strip_tags(html: &str) -> String {
    let mut out = String::with_capacity(html.len());
    let mut depth = 0usize;
    for ch in html.chars() {
        match ch {
            '<' => depth += 1,
            '>' => depth = depth.saturating_sub(1),
            _ if depth == 0 => out.push(ch),
            _ => {}
        }
    }
    collapse_ws(&decode_entities(&out))
}

/// Extracts (href, label) pairs from the EPUB3 navigation document.
fn parse_nav(html: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let mut rest = html;
    while let Some(start) = rest.find("<a") {
        rest = &rest[start..];
        let Some(gt) = rest.find('>') else { break };
        let tag = &rest[..gt];
        let Some(href) = attr_value(tag, "href") else {
            rest = &rest[gt + 1..];
            continue;
        };
        let after = &rest[gt + 1..];
        let inner = match after.find("</a>") {
            Some(end) => &after[..end],
            None => after,
        };
        let label = strip_tags(inner);
        if !label.is_empty() {
            out.push((href, label));
        }
        rest = after;
    }
    out
}

/// Extracts (src, label) pairs from an EPUB2 NCX document.
fn parse_ncx(xml: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let mut last_label = String::new();
    let mut rest = xml;
    while let Some(start) = rest.find('<') {
        rest = &rest[start..];
        let Some(end) = rest.find('>') else { break };
        let raw = &rest[1..end];
        let (closing, name) = tag_name(raw);
        if !closing && name == "text" {
            let after = &rest[end + 1..];
            if let Some(close) = after.find("</text>") {
                last_label = strip_tags(&after[..close]);
            }
        } else if !closing && name == "content" {
            if let Some(src) = attr_value(raw, "src") {
                if !last_label.is_empty() {
                    out.push((src, last_label.clone()));
                    last_label.clear();
                }
            }
        }
        rest = &rest[end + 1..];
    }
    out
}

fn chapter_title_for(blocks: &[FeedBlock], path: &str) -> String {
    blocks
        .iter()
        .find(|b| b.level > 0 && !b.text.is_empty())
        .map(|b| b.text.clone())
        .unwrap_or_else(|| {
            Path::new(path)
                .file_stem()
                .map(|s| s.to_string_lossy().to_string())
                .unwrap_or_else(|| path.to_string())
        })
}

/// Parses an EPUB file into chapters.  Returns an error for non-EPUB files so
/// the feed can skip them and pick another book.
pub fn parse_epub(path: &Path) -> Result<ParsedBook> {
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    if ext != "epub" {
        return Err(anyhow!("unsupported ebook format: {ext}"));
    }
    let file = std::fs::File::open(path).with_context(|| format!("open {}", path.display()))?;
    let mut archive = zip::ZipArchive::new(file).context("read epub zip")?;

    let container = read_entry(&mut archive, "META-INF/container.xml")
        .context("missing META-INF/container.xml")?;
    let container = String::from_utf8_lossy(&container).to_string();
    let opf_path = container
        .find("<rootfile")
        .and_then(|start| {
            let tag_end = container[start..].find('>')? + start;
            attr_value(&container[start..tag_end], "full-path")
        })
        .or_else(|| attr_value(&container, "full-path"))
        .ok_or_else(|| anyhow!("container.xml has no rootfile"))?;

    let opf_bytes = read_entry(&mut archive, &opf_path)
        .ok_or_else(|| anyhow!("missing opf {opf_path}"))?;
    let opf = String::from_utf8_lossy(&opf_bytes).to_string();
    let opf_dir = Path::new(&opf_path)
        .parent()
        .map(|p| p.to_path_buf())
        .unwrap_or_default();

    let title = clean_meta(&opf, "dc:title").unwrap_or_else(|| {
        path.file_stem()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_default()
    });
    let author = clean_meta(&opf, "dc:creator").unwrap_or_default();

    // ---- manifest ----
    let mut manifest: HashMap<String, (String, String, String)> = HashMap::new(); // id -> (href, media-type, properties)
    let mut rest = opf.as_str();
    while let Some(start) = rest.find("<item ") {
        rest = &rest[start + 6..];
        let Some(end) = rest.find('>') else { break };
        let tag = &rest[..end];
        if let (Some(id), Some(href)) = (attr_value(tag, "id"), attr_value(tag, "href")) {
            manifest.insert(
                id,
                (
                    href,
                    attr_value(tag, "media-type").unwrap_or_default(),
                    attr_value(tag, "properties").unwrap_or_default(),
                ),
            );
        }
        rest = &rest[end + 1..];
    }

    // ---- spine ----
    let mut spine: Vec<String> = Vec::new();
    let mut rest = opf.as_str();
    while let Some(start) = rest.find("<itemref") {
        rest = &rest[start..];
        let Some(end) = rest.find('>') else { break };
        if let Some(idref) = attr_value(&rest[..end], "idref") {
            if let Some((href, _, _)) = manifest.get(&idref) {
                spine.push(normalize_path(&opf_dir, href));
            }
        }
        rest = &rest[end + 1..];
    }
    if spine.is_empty() {
        return Err(anyhow!("epub spine is empty"));
    }

    // ---- TOC titles (EPUB3 nav first, then NCX) ----
    let mut titles: HashMap<String, String> = HashMap::new();
    let nav_href = manifest
        .values()
        .find(|(_, _, props)| props.split_whitespace().any(|p| p == "nav"))
        .map(|(href, _, _)| href.clone());
    if let Some(href) = nav_href {
        let nav_path = normalize_path(&opf_dir, &href);
        if let Some(bytes) = read_entry(&mut archive, &nav_path) {
            let html = String::from_utf8_lossy(&bytes).to_string();
            for (link, label) in parse_nav(&html) {
                let resolved = normalize_path(&opf_dir, &link);
                titles.entry(resolved).or_insert(label);
            }
        }
    }
    if titles.is_empty() {
        let ncx_href = manifest
            .values()
            .find(|(_, media_type, _)| media_type == "application/x-dtbncx+xml")
            .map(|(href, _, _)| href.clone())
            .or_else(|| {
                let needle = "toc=\"";
                opf.find(needle).and_then(|pos| {
                    let start = pos + needle.len();
                    let end = opf[start..].find('"')? + start;
                    let id = &opf[start..end];
                    manifest.get(id).map(|(href, _, _)| href.clone())
                })
            });
        if let Some(href) = ncx_href {
            let ncx_path = normalize_path(&opf_dir, &href);
            if let Some(bytes) = read_entry(&mut archive, &ncx_path) {
                let xml = String::from_utf8_lossy(&bytes).to_string();
                for (src, label) in parse_ncx(&xml) {
                    let resolved = normalize_path(&opf_dir, &src);
                    titles.entry(resolved).or_insert(label);
                }
            }
        }
    }

    // ---- chapters ----
    let mut chapters = Vec::new();
    for href in &spine {
        let Some(bytes) = read_entry(&mut archive, href) else {
            continue;
        };
        let html = String::from_utf8_lossy(&bytes).to_string();
        let blocks = html_to_blocks(&html);
        if blocks.is_empty() {
            continue;
        }
        let title = titles
            .get(href)
            .cloned()
            .filter(|t| !t.trim().is_empty())
            .unwrap_or_else(|| chapter_title_for(&blocks, href));
        chapters.push(FeedChapter { title, blocks });
    }
    if chapters.is_empty() {
        return Err(anyhow!("epub has no readable chapters"));
    }

    Ok(ParsedBook {
        title,
        author,
        chapters,
    })
}

fn clean_meta(xml: &str, tag: &str) -> Option<String> {
    let open = format!("<{tag}");
    let start = xml.find(&open)?;
    let after_open = xml[start..].find('>')? + start + 1;
    let close = format!("</{tag}>");
    let end = xml[after_open..].find(&close)? + after_open;
    let text = collapse_ws(&decode_entities(&xml[after_open..end]));
    if text.is_empty() {
        None
    } else {
        Some(text)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_blocks_and_headings() {
        let html = r#"<html><body><h1>Chapter</h1><p>Hello &amp; world</p><div>second<br/>line</div>
        <script>var x = "<p>no</p>";</script><p>tail&nbsp;text</p></body></html>"#;
        let blocks = html_to_blocks(html);
        let texts: Vec<&str> = blocks.iter().map(|b| b.text.as_str()).collect();
        assert_eq!(texts, vec!["Chapter", "Hello & world", "second", "line", "tail text"]);
        assert_eq!(blocks[0].level, 1);
        assert_eq!(blocks[1].level, 0);
    }

    #[test]
    fn decodes_numeric_entities() {
        assert_eq!(decode_entities("&#20320;&#x597d;"), "你好");
        assert_eq!(decode_entities("a &amp; b"), "a & b");
    }

    #[test]
    fn normalizes_relative_paths() {
        let dir = Path::new("OEBPS/text");
        assert_eq!(normalize_path(dir, "../images/a.png"), "OEBPS/images/a.png");
        assert_eq!(normalize_path(dir, "c1.xhtml#top"), "OEBPS/text/c1.xhtml");
    }
}
