// Draws the app icons (the three bits of the wordmark, white on the primary color) and writes
// the PNG files the web app manifest and iOS need. Run from `frontend/`:
//
//   node scripts/generate-icons.mjs
//
// `sharp` is not a direct dependency; it is the copy Next.js installs for image optimization.
import { mkdir } from "node:fs/promises";
import sharp from "sharp";

const PRIMARY = "#2b63e3";
const SIZE = 512;

/**
 * @param scale  how many pixels one unit of the wordmark's 24-unit grid takes
 * @param radius corner radius of the background (0 fills the whole square)
 */
function icon(scale, radius) {
  const offset = (SIZE - 24 * scale) / 2;
  const bit = (x, opacity) =>
    `<rect x="${offset + x * scale}" y="${offset + 8.5 * scale}" width="${6 * scale}" height="${7 * scale}" ` +
    `rx="${1.5 * scale}" fill="#ffffff" opacity="${opacity}"/>`;
  return Buffer.from(
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${SIZE} ${SIZE}">` +
      `<rect width="${SIZE}" height="${SIZE}" rx="${radius}" fill="${PRIMARY}"/>` +
      bit(1, 0.4) +
      bit(9, 0.7) +
      bit(17, 1) +
      `</svg>`,
  );
}

const rounded = icon(14, 112);
// Maskable icons are cropped to any shape; only the middle 80% is guaranteed to stay visible.
const maskable = icon(11, 0);
// iOS rounds the corners itself and paints transparent pixels black.
const square = icon(14, 0);

await mkdir("public/icons", { recursive: true });
await Promise.all([
  sharp(rounded).resize(192).png().toFile("public/icons/icon-192.png"),
  sharp(rounded).resize(512).png().toFile("public/icons/icon-512.png"),
  sharp(maskable).resize(512).png().toFile("public/icons/icon-maskable-512.png"),
  sharp(square).resize(180).png().toFile("src/app/apple-icon.png"),
]);
