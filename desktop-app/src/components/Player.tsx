import { useEffect, useRef, useState } from "react";
import { formatDuration } from "../api";
import type { PlayItem } from "../player";
import { Cover } from "./Cover";
import { NextIcon, PauseIcon, PlayIcon, PrevIcon, VolumeIcon } from "./Icons";

interface PlayerBarProps {
  item: PlayItem;
  baseUrl: string;
  hasPrev: boolean;
  hasNext: boolean;
  onPrev: () => void;
  onNext: () => void;
}

export function PlayerBar({ item, baseUrl, hasPrev, hasNext, onPrev, onNext }: PlayerBarProps) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [playing, setPlaying] = useState(false);
  const [time, setTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolume] = useState(() => {
    const stored = Number(localStorage.getItem("aurora.volume"));
    return Number.isFinite(stored) && stored > 0 ? Math.min(1, stored) : 0.9;
  });
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;
    setError(null);
    setTime(0);
    setDuration(0);
    audio.load();
    const promise = audio.play();
    if (promise) {
      promise.catch((err: unknown) => {
        setError(String(err));
        setPlaying(false);
      });
    }
  }, [item.key]);

  useEffect(() => {
    if (audioRef.current) audioRef.current.volume = volume;
    localStorage.setItem("aurora.volume", String(volume));
  }, [volume]);

  const toggle = () => {
    const audio = audioRef.current;
    if (!audio) return;
    if (audio.paused) {
      const promise = audio.play();
      if (promise) promise.catch((err: unknown) => setError(String(err)));
    } else {
      audio.pause();
    }
  };

  const seek = (value: number) => {
    const audio = audioRef.current;
    if (!audio) return;
    audio.currentTime = value;
    setTime(value);
  };

  return (
    <div className="player-bar">
      <audio
        ref={audioRef}
        src={`${baseUrl}${item.url}`}
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => {
          if (hasNext) onNext();
          else setPlaying(false);
        }}
        onTimeUpdate={(event) => setTime(event.currentTarget.currentTime)}
        onLoadedMetadata={(event) => setDuration(event.currentTarget.duration || 0)}
        onError={() => setError("无法播放这个文件，请确认 ffmpeg 已安装（视频音轨需要）或文件仍可访问。")}
      />

      <div className="player-track">
        <Cover src={item.artwork ? `${baseUrl}${item.artwork}` : null} size={46} radius={8} alt={item.title} />
        <div className="player-meta">
          <span className="player-title" title={item.title}>{item.title}</span>
          <span className="player-subtitle" title={item.subtitle}>{item.subtitle}</span>
        </div>
      </div>

      <div className="player-center">
        <div className="player-controls">
          <button className="icon-btn" onClick={onPrev} disabled={!hasPrev} title="上一首">
            <PrevIcon width={16} height={16} />
          </button>
          <button className="play-button" onClick={toggle} title={playing ? "暂停" : "播放"}>
            {playing ? <PauseIcon width={18} height={18} /> : <PlayIcon width={18} height={18} />}
          </button>
          <button className="icon-btn" onClick={onNext} disabled={!hasNext} title="下一首">
            <NextIcon width={16} height={16} />
          </button>
        </div>
        <div className="seek-row">
          <span className="time">{formatDuration(time)}</span>
          <input
            className="seek"
            type="range"
            min={0}
            max={Math.max(duration, 1)}
            step={0.5}
            value={Math.min(time, duration || 0)}
            onChange={(event) => seek(Number(event.target.value))}
          />
          <span className="time">{formatDuration(duration)}</span>
        </div>
        {error && <span className="player-error">{error}</span>}
      </div>

      <div className="player-volume">
        <VolumeIcon width={16} height={16} />
        <input
          type="range"
          min={0}
          max={1}
          step={0.01}
          value={volume}
          onChange={(event) => setVolume(Number(event.target.value))}
        />
      </div>
    </div>
  );
}
