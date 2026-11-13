# Application icon

Artwork supplied by the project owner (`微信图片_20261112175007_487_2.png`). `musxi.png` uses the blue/cyan rounded-square design with a white/cyan musical M and play symbol. Its exterior black corners were removed using the built-in image generation editor; the blue interior remains opaque.

The selected built-in edit used these prompts, followed by alpha normalization during Windows asset export:

> Use case: background-extraction. Edit target: the supplied square Musxi music-player app icon. Remove ONLY the near-black background in the four exterior corners OUTSIDE the existing rounded-square blue icon silhouette, replacing it with real alpha transparency. Preserve the entire blue rounded-square shape and ALL its interior artwork exactly: the cyan and blue gradients and sweeping ribbons, white/cyan M musical note with right note head, cyan triangular play symbol, all positions, proportions, colors, details and framing. No redesign, no extra margin, no added shadow or light, no text, no changes within the icon, no holes inside blue shape. Output one square tightly framed icon, transparent outside its original rounded corners.

> Precise alpha-edge cleanup only of this app icon. Preserve its interior artwork and framing exactly. Outside the single large blue rounded-square silhouette, remove ALL isolated blue/cyan flecks, stray dots, edge fragments and noise. Transparent outer corners must be perfectly clean, with a smooth continuous antialiased rounded-square boundary, no tiny detached colored islands anywhere. Full real transparent alpha outside icon. Do NOT redesign, change the note, play triangle, blue ribbons, gradients, proportions, framing or interior color. Keep the main blue body full opaque, no internal transparency. No added shadow, no background.

`musxi.ico` contains 32-bit PNG frames at 16, 20, 24, 32, 40, 48, 64, 128 and 256 pixels. The application, tray and installer share this artwork. The Windows build applies it to a copy of the CEF bootstrap; the SDK file is not modified.
