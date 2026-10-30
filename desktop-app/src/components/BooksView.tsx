import { useMemo, useState } from "react";
import type { BookDto } from "../api";
import { formatBytes } from "../api";
import { Cover } from "./Cover";
import { SearchIcon } from "./Icons";

interface BooksViewProps {
  books: BookDto[];
  baseUrl: string;
  rootId: number | null;
}

export function BooksView({ books, baseUrl, rootId }: BooksViewProps) {
  const [query, setQuery] = useState("");

  const filtered = useMemo(
    () => books.filter((book) => rootId === null || book.rootId === rootId),
    [books, rootId],
  );

  const matches = useMemo(() => {
    const search = query.trim().toLowerCase();
    if (!search) return filtered;
    return filtered.filter((book) =>
      [book.title, book.author, book.relPath].join(" ").toLowerCase().includes(search),
    );
  }, [filtered, query]);

  if (filtered.length === 0) {
    return (
      <div className="empty-state">
        <h3>还没有书籍</h3>
        <p>添加一个书籍文件夹，支持 EPUB、MOBI、AZW3、PDF 与 TXT。EPUB 会自动读取书名、作者与封面。</p>
      </div>
    );
  }

  return (
    <div className="books-view">
      <div className="toolbar">
        <div className="search-box">
          <SearchIcon width={15} height={15} />
          <input
            value={query}
            placeholder="搜索书名或作者"
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
        <div className="toolbar-meta">{matches.length} 本</div>
      </div>

      <div className="book-list">
        {matches.map((book) => (
          <div className="book-row" key={book.path}>
            <Cover
              src={book.coverUrl ? `${baseUrl}${book.coverUrl}` : null}
              size={46}
              radius={6}
              alt={book.title}
            />
            <div className="book-row-main">
              <span className="book-row-title">{book.title}</span>
              <span className="book-row-sub">
                {book.author || "未知作者"} · {book.relPath}
              </span>
            </div>
            <span className="format-badge">{book.format.toUpperCase()}</span>
            <span className="book-row-size">{formatBytes(book.size)}</span>
          </div>
        ))}
        {matches.length === 0 && <div className="empty-hint">没有匹配的书籍</div>}
      </div>
    </div>
  );
}
