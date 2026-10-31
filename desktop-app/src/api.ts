import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

export type RootKind = "music" | "audiobook" | "book";
export type MusicMode = "tag" | "folder";
export type Tab = "music" | "audiobooks" | "books";

export interface RootInfo {
  id: number;
  path: string;
  kind: RootKind;
  musicMode: MusicMode | null;
  fastScan: boolean;
  displayName: string;
  createdAt: number;
  lastScanAt: number | null;
  scanState: string;
  itemCount: number;
}

export interface TrackDto {
  id: string;
  title: string;
  artist: string;
  album: string;
  albumId: string;
  artistId: string;
  durationSec: number;
  trackNumber: number;
  discNumber: number;
  hasArt: boolean;
  mergeTitles: boolean;
  path: string;
  fileName: string;
  size: number;
  fromVideo: boolean;
  rootId: number;
  artUrl: string | null;
}

export interface AlbumGroup {
  id: string;
  title: string;
  artist: string;
  coverTrackId: string | null;
  durationSec: number;
  tracks: TrackDto[];
}

export interface ArtistGroup {
  id: string;
  name: string;
  trackCount: number;
  albums: AlbumGroup[];
}

export interface MusicLibrary {
  pathOrder: boolean;
  totalTracks: number;
  artists: ArtistGroup[];
}

export interface ChapterDto {
  path: string;
  title: string;
  trackNumber: number;
  durationSec: number;
  size: number;
  fromVideo: boolean;
  audioUrl: string;
}

export interface AudiobookDto {
  id: string;
  rootId: number;
  title: string;
  relPath: string;
  chapterCount: number;
  durationSec: number;
  hasArt: boolean;
  coverUrl: string | null;
  chapters: ChapterDto[];
}

export interface BookDto {
  path: string;
  rootId: number;
  relPath: string;
  title: string;
  author: string;
  size: number;
  format: string;
  hasCover: boolean;
  coverUrl: string | null;
  downloadUrl: string;
}

export interface LibraryStats {
  roots: number;
  tracks: number;
  audiobooks: number;
  chapters: number;
  books: number;
  videoTracks: number;
  libraryVersion: number;
}

export interface ServerInfo {
  deviceId: string;
  deviceName: string;
  port: number;
  baseUrl: string;
  startedAt: number;
}

export interface ScanProgress {
  rootId: number;
  rootName: string;
  phase: string;
  scanned: number;
  total: number;
}

export interface ScanSummary {
  rootId: number;
  rootName: string;
  kind: RootKind;
  added: number;
  updated: number;
  removed: number;
  skipped: number;
  durationMs: number;
  errors: string[];
}

export const api = {
  getServerInfo: () => invoke<ServerInfo>("get_server_info"),
  listRoots: () => invoke<RootInfo[]>("list_roots"),
  addRoot: (
    path: string,
    kind: RootKind,
    musicMode: MusicMode | null,
    displayName: string | null,
    fastScan: boolean,
  ) => invoke<RootInfo>("add_root", { path, kind, musicMode, displayName, fastScan }),
  updateRoot: (
    id: number,
    displayName: string | null,
    musicMode: MusicMode | null,
    fastScan: boolean | null,
  ) => invoke<RootInfo>("update_root", { id, displayName, musicMode, fastScan }),
  removeRoot: (id: number) => invoke<void>("remove_root", { id }),
  scanRoots: (ids: number[]) => invoke<ScanSummary[]>("scan_roots", { ids }),
  musicLibrary: () => invoke<MusicLibrary>("music_library"),
  listAudiobooks: () => invoke<AudiobookDto[]>("list_audiobooks"),
  listBooks: () => invoke<BookDto[]>("list_books"),
  libraryStats: () => invoke<LibraryStats>("library_stats"),
};

export function onScanProgress(handler: (progress: ScanProgress) => void): Promise<UnlistenFn> {
  return listen<ScanProgress>("scan:progress", (event) => handler(event.payload));
}

export function onScanDone(handler: (summary: ScanSummary) => void): Promise<UnlistenFn> {
  return listen<ScanSummary>("scan:done", (event) => handler(event.payload));
}

export function formatDuration(seconds: number): string {
  if (!seconds || seconds <= 0) return "--:--";
  const total = Math.round(seconds);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h > 0) return `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
  return `${m}:${String(s).padStart(2, "0")}`;
}

export function formatBytes(bytes: number): string {
  if (!bytes) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value >= 10 || unit === 0 ? 0 : 1)} ${units[unit]}`;
}

export const kindLabel: Record<RootKind, string> = {
  music: "音乐",
  audiobook: "有声书",
  book: "书籍",
};

export const modeLabel: Record<MusicMode, string> = {
  tag: "ID3 标签",
  folder: "艺人/专辑目录",
};
