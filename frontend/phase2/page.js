const byId = id => document.getElementById(id);
const time = ms => Math.floor(ms / 60000) + ":" + String(Math.floor(ms / 1000) % 60).padStart(2, "0");
let pending = false;
async function refresh() {
  if (pending) return;
  pending = true;
  try {
    const state = await window.musxiNative.getState();
    byId("connection").textContent = "已连接 · 本机播放器";
    byId("playback").textContent = state.opened ? (state.playing ? "正在播放" : "已暂停") : "尚未播放歌曲";
    byId("position").textContent = time(state.positionMs) + " / " + time(state.durationMs);
    byId("volume").textContent = state.volumePercent + "%";
  } catch (error) { byId("connection").textContent = error.message; }
  finally { pending = false; }
}
byId("refresh").onclick = refresh;
refresh();
setInterval(refresh, 500);
// End-to-end smoke: a real Renderer validates Browser responses and rejects.
async function verifyBridge() {
  await window.musxiNative.getState();
  for (const [command, params, expected] of [
    ["player.unknown", {}, 404], ["player.getState", { bad: true }, 400]
  ]) {
    let rejected = false;
    try { await window.musxiNative.request(command, params); }
    catch (error) { if (error.code !== expected) throw error; rejected = true; }
    if (!rejected) throw new Error("Invalid request accepted");
  }
  // Only recognized in a host explicitly launched with --cef-smoke.
  await window.musxiNative.request("test.complete");
}
if (new URLSearchParams(location.search).has("smoke")) verifyBridge().catch(() => {});
