Static 320×180 test patterns, generated with the existing development FFmpeg:

`ffmpeg -f lavfi -i testsrc2=size=320x180:rate=1 -frames:v 1 test.<png|jpg|webp>`

No external image assets or additional runtime dependencies.

`invalid.png` keeps the PNG container and dimensions but omits pixel data, to verify decode failure preserves the previous valid candidate.

Additional scaling and alpha fixtures:

```text
ffmpeg -f lavfi -i "color=c=steelblue:size=6000x3000,format=rgb24" -frames:v 1 large.png
ffmpeg -f lavfi -i "color=c=red@0.5:size=128x64,format=rgba" -frames:v 1 alpha.png
ffmpeg -i alpha.png -c:v libwebp -lossless 1 alpha.webp
```
