export const L = { NONE: 0, LATCHED: 1, LOCKED: 2 } as const;

export type ModLevel = (typeof L)[keyof typeof L];

export interface ModState {
  ctrl: ModLevel;
  alt: ModLevel;
  shift: ModLevel;
  caps: ModLevel;
  fn: ModLevel;
}

export function createModState(): ModState {
  return { ctrl: L.NONE, alt: L.NONE, shift: L.NONE, caps: L.NONE, fn: L.NONE };
}

export function toggleMod(s: ModState, mod: keyof ModState): ModState {
  const next = { ...s };
  if (next[mod] === L.NONE) next[mod] = L.LATCHED;
  else if (next[mod] === L.LATCHED) next[mod] = L.LOCKED;
  else next[mod] = L.NONE;
  return next;
}

export function clearLatched(s: ModState): ModState {
  const next = { ...s };
  for (const m of ['ctrl', 'alt', 'shift', 'caps'] as const) {
    if (next[m] === L.LATCHED) next[m] = L.NONE;
  }
  return next;
}

export function effective(s: ModState) {
  return {
    ctrl: s.ctrl !== L.NONE,
    alt: s.alt !== L.NONE,
    shift: s.shift !== L.NONE,
    caps: s.caps !== L.NONE,
    fn: s.fn !== L.NONE,
  };
}
