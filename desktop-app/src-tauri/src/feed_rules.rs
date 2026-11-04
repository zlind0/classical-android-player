//! User-editable JavaScript rules for the random-reading feed.
//!
//! Every random position is offered to a small JS function:
//!
//! ```js
//! function accept(ctx) { return true; }
//! ```
//!
//! The default script implements the "skip boring chapters" heuristics (TOC,
//! copyright pages, footnote lists, URL-heavy paragraphs, ...).  The script is
//! stored next to `library.db` so a user can edit it from the desktop settings
//! page without recompiling the app.

use anyhow::{anyhow, Result};
use boa_engine::{js_string, Context, JsValue, Source};
use serde_json::Value;

pub const DEFAULT_RULES: &str = r#"// 随机阅读规则
// accept(ctx) 返回 true 表示接受本次随机结果，返回 false 表示重新随机。
// 修改后保存即可，下一次随机立即生效；「恢复默认」会覆盖这里的所有内容。
//
// ctx 字段：
//   ctx.book.title / ctx.book.author  书名、作者
//   ctx.chapterIndex                  章节下标
//   ctx.chapterTitle                  章节标题
//   ctx.blockIndex                    本次随机到的段落在 ctx.blocks 中的下标
//   ctx.blocks                        整章的所有块 [{ index, text, level }]
//                                     level 0 是正文 <p>，1..6 是标题
//   ctx.headingBlocks                 当前标题下的正文段落 [{ index, text }]
//   ctx.headingIndex                  本次随机段落在此标题段落数组中的位置
//   ctx.headingParagraphs             当前标题下的正文段落数量
function accept(ctx) {
  var BEFORE = 5;        // 前后各取几个段落做语言/长度检查
  var URL_BEFORE = 10;   // 前后各取几个段落做网址检查
  var MIN_AVG_LENGTH = 10;
  var MIN_HEADING_PARAGRAPHS = 10;
  var MAX_LATIN_RATIO = 0.10;

  var blocks = ctx.headingBlocks || [];
  var picked = ctx.headingIndex;
  if (picked < 0 || picked >= blocks.length) return false;

  // 1. 章节标题不得包含这些关键字
  var bannedTitles = [
    "目录", "版权信息", "封面", "读累了记得休息一会", "附录", "后记",
    "致谢", "序言", "出版说明", "关于本书", "参考文献", "致谢"
  ];
  var title = ctx.chapterTitle || "";
  for (var i = 0; i < bannedTitles.length; i++) {
    if (title.indexOf(bannedTitles[i]) >= 0) return false;
  }

  // 2. 不能是脚注/参考资料那样的一串前导序号段落。
  //    只要本段落带序号，并且前后还有至少 3 个同样带序号的段落，就认为命中。
  var serial = /^\s*(?:[\[\(（【〔<《{｛〈]\s*(?:[0-9０-９]{1,4}|[①-⑳])\s*[\]\)）】〕>》}｝〉]|[①-⑳]|[0-9０-９]{1,4}[、.．:：\)）])\s*\S/;
  function looksSerial(text) {
    return serial.test(text || "");
  }
  if (looksSerial(blocks[picked].text)) {
    var serialCount = 0;
    var from = Math.max(0, picked - BEFORE);
    var to = Math.min(blocks.length, picked + BEFORE + 1);
    for (var s = from; s < to; s++) {
      if (looksSerial(blocks[s].text)) serialCount++;
    }
    if (serialCount >= 3) return false;
  }

  var around = blocks.slice(Math.max(0, picked - BEFORE), Math.min(blocks.length, picked + BEFORE + 1));

  // 3. 前后段落要么是纯西文；一旦出现中文，西文字母不得超过 10%。
  var cjk = 0;
  var latin = 0;
  for (var a = 0; a < around.length; a++) {
    var text = around[a].text || "";
    for (var c = 0; c < text.length; c++) {
      var code = text.charCodeAt(c);
      if (code >= 0x4e00 && code <= 0x9fff) cjk++;
      if ((code >= 65 && code <= 90) || (code >= 97 && code <= 122)) latin++;
    }
  }
  if (cjk > 0 && latin / (cjk + latin) > MAX_LATIN_RATIO) return false;

  // 4. 前后段落的平均长度不得小于 10 个字符
  if (around.length === 0) return false;
  var totalLength = 0;
  for (var l = 0; l < around.length; l++) {
    totalLength += (around[l].text || "").length;
  }
  if (totalLength / around.length < MIN_AVG_LENGTH) return false;

  // 5. 整个标题下面的正文段落数量不得小于 10
  if ((ctx.headingParagraphs || 0) < MIN_HEADING_PARAGRAPHS) return false;

  // 6. 前后段落中不得包含网址
  var wider = blocks.slice(Math.max(0, picked - URL_BEFORE), Math.min(blocks.length, picked + URL_BEFORE + 1));
  var url = /(?:https?:\/\/|www\.)[^\s]+/i;
  for (var u = 0; u < wider.length; u++) {
    if (url.test(wider[u].text || "")) return false;
  }

  return true;
}
"#;

/// One evaluated script.  Boa contexts are not `Send`, so this type is created
/// inside the blocking task that needs it and never stored in `FeedService`.
pub struct RuleEngine {
    context: Context,
}

impl RuleEngine {
    pub fn new(script: &str) -> Result<Self> {
        let mut context = Context::default();
        context
            .eval(Source::from_bytes(script))
            .map_err(|err| anyhow!("随机规则脚本语法错误: {err}"))?;
        let function = context
            .global_object()
            .get(js_string!("accept"), &mut context)
            .map_err(|err| anyhow!("随机规则脚本错误: {err}"))?;
        if !function.is_callable() {
            return Err(anyhow!("随机规则脚本必须定义函数 accept(ctx)"));
        }
        Ok(RuleEngine { context })
    }

    pub fn accepts(&mut self, payload: &Value) -> Result<bool> {
        let json = serde_json::to_string(payload)?;
        let argument = self
            .context
            .eval(Source::from_bytes(&format!("({json})")))
            .map_err(|err| anyhow!("随机规则参数错误: {err}"))?;
        let function = self
            .context
            .global_object()
            .get(js_string!("accept"), &mut self.context)
            .map_err(|err| anyhow!("随机规则脚本错误: {err}"))?;
        let callable = function
            .as_callable()
            .ok_or_else(|| anyhow!("随机规则脚本必须定义函数 accept(ctx)"))?;
        let result = callable
            .call(&JsValue::undefined(), &[argument], &mut self.context)
            .map_err(|err| anyhow!("随机规则脚本运行错误: {err}"))?;
        Ok(result.to_boolean())
    }
}

/// Validates a script without keeping the engine around.  Used by the settings
/// command before persisting a new version.
pub fn validate(script: &str) -> Result<()> {
    RuleEngine::new(script).map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn payload(title: &str, paragraphs: &[&str], picked: usize) -> Value {
        let blocks: Vec<Value> = paragraphs
            .iter()
            .enumerate()
            .map(|(index, text)| json!({ "index": index, "text": text }))
            .collect();
        json!({
            "book": { "title": "b", "author": "a" },
            "chapterIndex": 0,
            "chapterTitle": title,
            "blockIndex": picked,
            "blocks": blocks,
            "headingBlocks": blocks,
            "headingIndex": picked,
            "headingParagraphs": paragraphs.len(),
        })
    }

    fn accept(payload: &Value) -> bool {
        RuleEngine::new(DEFAULT_RULES)
            .unwrap()
            .accepts(payload)
            .unwrap()
    }

    #[test]
    fn default_rules_compile() {
        validate(DEFAULT_RULES).unwrap();
    }

    #[test]
    fn rejects_script_without_accept() {
        assert!(validate("var x = 1;").is_err());
    }

    #[test]
    fn accepts_plain_chinese_chapter() {
        let text = "这是一个正常的段落，用来测试随机规则是否可以通过。";
        let paragraphs = vec![text; 12];
        assert!(accept(&payload("第一章", &paragraphs, 5)));
    }

    #[test]
    fn rejects_boring_title() {
        let text = "这是一个正常的段落，用来测试随机规则是否可以通过。";
        let paragraphs = vec![text; 12];
        assert!(!accept(&payload("目录", &paragraphs, 5)));
    }

    #[test]
    fn rejects_short_heading() {
        let text = "这是一个正常的段落，用来测试随机规则是否可以通过。";
        let paragraphs = vec![text; 4];
        assert!(!accept(&payload("第一章", &paragraphs, 1)));
    }

    #[test]
    fn rejects_url_window() {
        let text = "这是一个正常的段落，用来测试随机规则是否可以通过。";
        let mut paragraphs = vec![text; 12];
        paragraphs[8] = "详见 https://example.com/page 的说明。";
        assert!(!accept(&payload("第一章", &paragraphs, 2)));
    }

    #[test]
    fn rejects_footnote_serial_list() {
        let text = "这是一个正常的段落，用来测试随机规则是否可以通过。";
        let mut paragraphs = vec![text; 12];
        for (offset, paragraph) in paragraphs.iter_mut().enumerate().take(6).skip(1) {
            *paragraph = Box::leak(format!("[{offset}] 注释内容").into_boxed_str());
        }
        assert!(!accept(&payload("第一章", &paragraphs, 3)));
    }

    #[test]
    fn rejects_latin_heavy_mixed_text() {
        let english =
            "This paragraph is written mostly in English and should trigger the ratio rule.";
        let mut paragraphs =
            vec!["这是一段正常的中文内容，用来满足段落数量要求。"; 12];
        paragraphs[4] = english;
        assert!(!accept(&payload("第一章", &paragraphs, 5)));
    }
}
