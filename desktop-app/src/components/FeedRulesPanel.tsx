import { useEffect, useState } from "react";
import { api } from "../api";

interface FeedRulesPanelProps {
  onClose: () => void;
  onToast: (message: string) => void;
}

const DRAFT_KEY = "aurora.feedRulesDraft";

/// Settings page for the JavaScript that decides whether a random position is
/// interesting.  The draft is kept locally so closing the panel does not throw
/// away unsaved edits.
export function FeedRulesPanel({ onClose, onToast }: FeedRulesPanelProps) {
  const [script, setScript] = useState("");
  const [savedScript, setSavedScript] = useState("");
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    api
      .getFeedRules()
      .then((rules) => {
        if (!alive) return;
        const draft = localStorage.getItem(DRAFT_KEY);
        setScript(draft && draft !== rules.script ? draft : rules.script);
        setSavedScript(rules.script);
        setError(null);
      })
      .catch((err) => {
        if (alive) setError(String(err));
      })
      .finally(() => {
        if (alive) setLoaded(true);
      });
    return () => {
      alive = false;
    };
  }, []);

  const change = (value: string) => {
    setScript(value);
    localStorage.setItem(DRAFT_KEY, value);
  };

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const rules = await api.saveFeedRules(script);
      localStorage.removeItem(DRAFT_KEY);
      setScript(rules.script);
      setSavedScript(rules.script);
      onToast("随机规则已保存，下一次随机生效");
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  };

  const reset = async () => {
    if (!window.confirm("恢复默认规则会覆盖当前编辑内容，确定继续？")) return;
    setBusy(true);
    setError(null);
    try {
      const rules = await api.resetFeedRules();
      localStorage.removeItem(DRAFT_KEY);
      setScript(rules.script);
      setSavedScript(rules.script);
      onToast("已恢复默认随机规则");
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  };

  const dirty = loaded && script !== savedScript;

  return (
    <div className="relay-overlay" onClick={onClose}>
      <div className="relay-card feed-rules-card" onClick={(event) => event.stopPropagation()}>
        <header className="relay-head">
          <div>
            <h2>随机阅读规则</h2>
            <p>
              用 JavaScript 决定随机到的章节是否值得阅读。脚本必须定义
              <code> accept(ctx) </code>
              并返回 true / false；返回 false 时会在同一本书里重新随机，最多 5 次，仍不满足就换一本书。
            </p>
          </div>
          <button className="icon-btn" onClick={onClose} title="关闭">
            ×
          </button>
        </header>

        {error && <div className="banner error">{error}</div>}

        <textarea
          className="feed-rules-editor"
          spellCheck={false}
          value={script}
          disabled={!loaded || busy}
          onChange={(event) => change(event.target.value)}
        />

        <div className="feed-rules-hint">
          <span>
            ctx 包含 <code>chapterTitle</code>、<code>headingBlocks</code>、
            <code>headingIndex</code>、<code>headingParagraphs</code> 等字段，默认脚本里有完整注释。
          </span>
          <span className={dirty ? "feed-rules-state dirty" : "feed-rules-state"}>
            {dirty ? "有未保存的修改" : "已保存"}
          </span>
        </div>

        <div className="relay-actions spread">
          <button className="btn ghost" disabled={busy || !loaded} onClick={reset}>
            恢复默认 JS
          </button>
          <div className="relay-actions">
            <button className="btn ghost" disabled={busy} onClick={onClose}>
              关闭
            </button>
            <button className="btn primary" disabled={busy || !loaded} onClick={save}>
              {busy ? "保存中…" : "保存"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
