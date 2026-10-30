import { useState } from "react";
import { MusicIcon } from "./Icons";

interface CoverProps {
  src: string | null;
  size?: number;
  radius?: number;
  className?: string;
  alt?: string;
}

export function Cover({ src, size = 44, radius = 8, className = "", alt = "" }: CoverProps) {
  const [failed, setFailed] = useState(false);
  const showImage = src && !failed;
  return (
    <span
      className={`cover ${className}`}
      style={{ width: size, height: size, borderRadius: radius }}
    >
      {showImage ? (
        <img src={src} alt={alt} loading="lazy" onError={() => setFailed(true)} />
      ) : (
        <span className="cover-fallback"><MusicIcon width={size * 0.5} height={size * 0.5} /></span>
      )}
    </span>
  );
}
