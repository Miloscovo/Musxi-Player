export function vinylColor(pixels: Uint8ClampedArray) {
  let red = 0, green = 0, blue = 0, total = 0;
  for (let i = 0; i + 3 < pixels.length; i += 4) {
    const r = pixels[i], g = pixels[i + 1], b = pixels[i + 2], alpha = pixels[i + 3] / 255;
    const high = Math.max(r, g, b), low = Math.min(r, g, b);
    if (!alpha || high < 32 || low > 245) continue;
    const weight = alpha * (1 + (high - low) / 32);
    red += r * weight; green += g * weight; blue += b * weight; total += weight;
  }
  return total ? `rgb(${Math.round(red / total)}, ${Math.round(green / total)}, ${Math.round(blue / total)})` : '';
}

export function sampleCoverColor(image: HTMLImageElement) {
  try {
    const canvas = document.createElement('canvas'); canvas.width = canvas.height = 32;
    const context = canvas.getContext('2d'); if (!context) return '';
    context.drawImage(image, 0, 0, 32, 32);
    return vinylColor(context.getImageData(0, 0, 32, 32).data);
  } catch { return ''; }
}
