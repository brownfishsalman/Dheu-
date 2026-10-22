// Builds the Android launcher icon from the website's transparent logo mark
// (public/brand/mark.png): an adaptive-icon foreground and a monochrome
// layer (Android 13 themed icons) at every density, plus a 512 px Play Store
// icon and the white notification icon (ic_stat_dheu). The navy background
// is a colour resource. Run from the repo root:
//   node android/tools/generate-launcher-icons.mjs
import sharp from "sharp";
import { mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const res = join(here, "..", "app", "src", "main", "res");
const MARK = join(root, "public", "brand", "mark.png");
const TILE = join(root, "public", "brand", "tile-1024.png");

// Adaptive icons are 108dp squares; launchers mask everything outside the
// central 72dp, so the pin sits at about half the canvas height.
const DENSITIES = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };
const PIN_SCALE = 0.5;

async function layer(size, { monochrome = false } = {}) {
  let pin = sharp(MARK).resize({ height: Math.round(size * PIN_SCALE) });
  if (monochrome) {
    // White silhouette: keep only the alpha channel.
    const { data, info } = await pin.ensureAlpha().raw().toBuffer({ resolveWithObject: true });
    for (let i = 0; i < data.length; i += 4) data[i] = data[i + 1] = data[i + 2] = 255;
    pin = sharp(data, { raw: info });
  }
  const buf = await pin.png().toBuffer();
  const meta = await sharp(buf).metadata();
  return sharp({ create: { width: size, height: size, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } } })
    .composite([{ input: buf, left: Math.round((size - meta.width) / 2), top: Math.round((size - meta.height) / 2) }])
    .png({ compressionLevel: 9 });
}

for (const [density, factor] of Object.entries(DENSITIES)) {
  const size = Math.round(108 * factor);
  const dir = join(res, `mipmap-${density}`);
  mkdirSync(dir, { recursive: true });
  await (await layer(size)).toFile(join(dir, "ic_launcher_foreground.png"));
  await (await layer(size, { monochrome: true })).toFile(join(dir, "ic_launcher_monochrome.png"));
}

// Notification icon: a 24dp white silhouette (Android tints it), filling most of the square.
for (const [density, factor] of Object.entries(DENSITIES)) {
  const size = Math.round(24 * factor);
  const { data, info } = await sharp(MARK).resize({ height: Math.round(size * 0.92) }).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  for (let i = 0; i < data.length; i += 4) data[i] = data[i + 1] = data[i + 2] = 255;
  const glyph = await sharp(data, { raw: info }).png().toBuffer();
  const dir = join(res, `drawable-${density}`);
  mkdirSync(dir, { recursive: true });
  await sharp({ create: { width: size, height: size, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } } })
    .composite([{ input: glyph, left: Math.round((size - info.width) / 2), top: Math.round((size - info.height) / 2) }])
    .png({ compressionLevel: 9 })
    .toFile(join(dir, "ic_stat_dheu.png"));
}

await sharp(TILE).resize(512, 512).png({ compressionLevel: 9 }).toFile(join(here, "..", "play-store-icon-512.png"));
console.log("launcher icons written");
