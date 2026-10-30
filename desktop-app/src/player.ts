export interface PlayItem {
  key: string;
  title: string;
  subtitle: string;
  url: string;
  artwork: string | null;
}

export interface PlayerState {
  queue: PlayItem[];
  index: number;
}

export function currentItem(player: PlayerState | null): PlayItem | null {
  if (!player || player.queue.length === 0) return null;
  return player.queue[Math.max(0, Math.min(player.index, player.queue.length - 1))] ?? null;
}
