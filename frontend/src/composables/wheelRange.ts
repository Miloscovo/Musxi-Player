// Keep a burst's target until the command completes, rather than accumulating from stale player events.
export function bindWheelRange(input: HTMLInputElement, commit: (value: number) => Promise<unknown>, draft: (value: number | null) => void, position: () => number | null, step: number, tolerance: number) {
  let target: number | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let frame = 0;
  let displayed = Number(input.value), from = displayed, started = 0;
  let completed = false;
  let revision = 0;
  function cancel() {
    const active = target !== null;
    clearTimeout(timer); cancelAnimationFrame(frame); frame = 0; target = null; ++revision;
    if (active) draft(null);
  }
  function animate(now: number) {
    const progress = Math.min(1, (now - started) / 160);
    displayed = from + (target! - from) * (1 - (1 - progress) ** 3);
    draft(displayed);
    const actual = position();
    // Hand off only after native state catches up; failed commands must not leave a frozen preview.
    if (progress === 1 && completed && ((actual !== null && Math.abs(actual - target!) < tolerance) || now - started > 2000)) cancel();
    else frame = requestAnimationFrame(animate);
  }
  function wheel(event: WheelEvent) {
    if (input.disabled || !event.deltaY || event.ctrlKey || event.metaKey) return;
    event.preventDefault();
    if (target === null) displayed = Number(input.value);
    from = displayed; started = performance.now(); completed = false;
    target = Math.max(Number(input.min), Math.min(Number(input.max),
      (target ?? Number(input.value)) + (event.deltaY > 0 ? step : -step)));
    cancelAnimationFrame(frame); frame = requestAnimationFrame(animate);
    const current = ++revision;
    clearTimeout(timer);
    timer = setTimeout(async () => {
      try { await commit(target!); }
      finally { if (revision === current) completed = true; }
    }, 80);
  }
  const controls = input.parentElement ?? input;
  input.addEventListener('wheel', wheel, { passive: false });
  controls.addEventListener('pointerdown', cancel);
  controls.addEventListener('click', cancel);
  input.addEventListener('change', cancel);
  return () => {
    cancel();
    input.removeEventListener('wheel', wheel);
    controls.removeEventListener('pointerdown', cancel);
    controls.removeEventListener('click', cancel);
    input.removeEventListener('change', cancel);
  };
}
