// Generates the fallback cover shown when a track has no embedded artwork.
// Run: node scripts/gen-default-cover.mjs
import { deflateSync } from "node:zlib";
import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const out = resolve(here, "../src-tauri/assets/default-cover.png");

const SIZE = 512;
const px = Buffer.alloc(SIZE * SIZE * 4);
const cx = SIZE / 2;
const cy = SIZE / 2;

for (let y = 0; y < SIZE; y++) {
  for (let x = 0; x < SIZE; x++) {
    const i = (y * SIZE + x) * 4;
    const t = (x + y) / (SIZE * 2);
    // Soft light gradient, matching the light UI theme.
    let r = Math.round(243 - 21 * t);
    let g = Math.round(246 - 17 * t);
    let b = Math.round(253 - 8 * t);

    const dx = x - cx;
    const dy = y - cy;
    const dist = Math.sqrt(dx * dx + dy * dy);

    if (dist > 196) {
      // Outside the disc: keep the background, slightly darker at the edge.
      if (dist < 206) {
        r = Math.round(r * 0.97);
        g = Math.round(g * 0.97);
        b = Math.round(b * 0.99);
      }
    } else if (dist > 188) {
      // Outer rim.
      r = 195;
      g = 203;
      b = 238;
    } else {
      // Light vinyl surface.
      r = 230;
      g = 235;
      b = 251;
      if (Math.abs((dist % 16) - 8) < 1.2) {
        r -= 10;
        g -= 9;
        b -= 6;
      }
      if (dist < 46) {
        // Center label.
        r = 255;
        g = 255;
        b = 255;
      }
      if (dist >= 40 && dist < 46) {
        // Accent ring around the label.
        r = 90;
        g = 99;
        b = 232;
      }
      if (dist < 11) {
        r = 90;
        g = 99;
        b = 232;
      }
    }
    px[i] = Math.min(255, r);
    px[i + 1] = Math.min(255, g);
    px[i + 2] = Math.min(255, b);
    px[i + 3] = 255;
  }
}

// Add per-row filter byte (0 = none).
const raw = Buffer.alloc(SIZE * (SIZE * 4 + 1));
for (let y = 0; y < SIZE; y++) {
  raw[y * (SIZE * 4 + 1)] = 0;
  px.copy(raw, y * (SIZE * 4 + 1) + 1, y * SIZE * 4, (y + 1) * SIZE * 4);
}

const crcTable = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = crcTable[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(SIZE, 0);
ihdr.writeUInt32BE(SIZE, 4);
ihdr[8] = 8; // bit depth
ihdr[9] = 6; // RGBA
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk("IHDR", ihdr),
  chunk("IDAT", deflateSync(raw, { level: 9 })),
  chunk("IEND", Buffer.alloc(0)),
]);

mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, png);
console.log(`wrote ${out} (${png.length} bytes)`);
