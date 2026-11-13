Original Musxi test fixtures; no third-party artwork. The scene draws a colored rectangle with a scripted opacity. The silent video was generated with:

```powershell
ffmpeg -f lavfi -i "testsrc2=size=160x90:rate=15" -t 2 -an -c:v libvpx-vp9 -b:v 40k video/video.webm
```

The two-frame thumbnail inputs use `testsrc2=size=160x90:rate=2` with `-frames:v 2`: `scene/preview.gif` uses the GIF encoder, `video/preview.webp` uses `-c:v libwebp_anim -loop 0`, and `animated.png` uses `-plays 0 -f apng`. The broken preview is intentionally plain text. The video project uses mixed-case `Video` to cover metadata normalization.

The scene script reports bridge isolation and received playback messages to the parent smoke test, so preview pause and cancel/resume are checked across the real iframe boundary. The suspension smoke test invokes the captured 60-second timer without waiting a minute.
