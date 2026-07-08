const {describe, it} = require('node:test');
const assert = require('node:assert');

const L = { NONE: 0, LATCHED: 1, LOCKED: 2 };

function createModState() {
  return { ctrl: L.NONE, alt: L.NONE, shift: L.NONE, caps: L.NONE, fn: L.NONE };
}

function toggleMod(s, mod) {
  const next = { ...s };
  if (next[mod] === L.NONE) next[mod] = L.LATCHED;
  else if (next[mod] === L.LATCHED) next[mod] = L.LOCKED;
  else next[mod] = L.NONE;
  return next;
}

function clearLatched(s) {
  const next = { ...s };
  for (const m of ['ctrl', 'alt', 'shift', 'caps']) {
    if (next[m] === L.LATCHED) next[m] = L.NONE;
  }
  return next;
}

function effective(s) {
  return {
    ctrl: s.ctrl !== L.NONE,
    alt: s.alt !== L.NONE,
    shift: s.shift !== L.NONE,
    caps: s.caps !== L.NONE,
    fn: s.fn !== L.NONE,
  };
}

function createLayerState() {
  return { active: 'base', latch: null, lock: null };
}

function toggleLayer(s, layer) {
  if (s.active === layer) {
    if (s.lock === layer) {
      return { active: 'base', latch: null, lock: null };
    }
    return { active: layer, latch: null, lock: layer };
  }
  if (s.lock !== null) {
    return { active: layer, latch: null, lock: null };
  }
  return { active: layer, latch: layer, lock: null };
}

function layerAfterKey(s) {
  if (s.latch !== null) {
    return { active: 'base', latch: null, lock: s.lock };
  }
  return s;
}

describe('ModifierState', () => {
  it('starts all NONE', () => {
    const s = createModState();
    for (const m of ['ctrl', 'alt', 'shift', 'caps', 'fn']) {
      assert.equal(s[m], L.NONE);
    }
  });

  it('toggles NONE -> LATCHED on first press', () => {
    const s = toggleMod(createModState(), 'ctrl');
    assert.equal(s.ctrl, L.LATCHED);
  });

  it('toggles LATCHED -> LOCKED on second press', () => {
    const s = toggleMod(toggleMod(createModState(), 'alt'), 'alt');
    assert.equal(s.alt, L.LOCKED);
  });

  it('toggles LOCKED -> NONE on third press', () => {
    const s = toggleMod(toggleMod(toggleMod(createModState(), 'shift'), 'shift'), 'shift');
    assert.equal(s.shift, L.NONE);
  });

  it('clearLatched resets latched but not locked', () => {
    let s = createModState();
    s.ctrl = L.LATCHED;
    s.alt = L.LOCKED;
    const c = clearLatched(s);
    assert.equal(c.ctrl, L.NONE);
    assert.equal(c.alt, L.LOCKED);
  });

  it('effective returns true for non-NONE', () => {
    let s = createModState();
    s.shift = L.LOCKED;
    const ef = effective(s);
    assert.equal(ef.shift, true);
    assert.equal(ef.ctrl, false);
    assert.equal(ef.alt, false);
  });

  it('multiple mods can be active', () => {
    let s = createModState();
    s.ctrl = L.LOCKED;
    s.alt = L.LATCHED;
    const ef = effective(s);
    assert.equal(ef.ctrl, true);
    assert.equal(ef.alt, true);
  });
});

describe('LayerState', () => {
  it('starts at base with no latch/lock', () => {
    const s = createLayerState();
    assert.equal(s.active, 'base');
    assert.equal(s.latch, null);
    assert.equal(s.lock, null);
  });

  it('toggleLayer latches on first press of different layer', () => {
    const s = toggleLayer(createLayerState(), 'lower');
    assert.equal(s.active, 'lower');
    assert.equal(s.latch, 'lower');
    assert.equal(s.lock, null);
  });

  it('toggleLayer locks on second press of same latched layer', () => {
    let s = toggleLayer(createLayerState(), 'raise');
    s = toggleLayer(s, 'raise');
    assert.equal(s.active, 'raise');
    assert.equal(s.latch, null);
    assert.equal(s.lock, 'raise');
  });

  it('toggleLayer unlocks back to base on third press', () => {
    let s = createLayerState();
    s = toggleLayer(s, 'adj');
    s = toggleLayer(s, 'adj');
    s = toggleLayer(s, 'adj');
    assert.equal(s.active, 'base');
    assert.equal(s.latch, null);
    assert.equal(s.lock, null);
  });

  it('switching layers clears previous lock', () => {
    let s = createLayerState();
    s = toggleLayer(s, 'lower'); // latch
    s = toggleLayer(s, 'lower'); // lock
    assert.equal(s.active, 'lower');
    assert.equal(s.lock, 'lower');

    s = toggleLayer(s, 'raise'); // switch to raise
    assert.equal(s.active, 'raise');
    assert.equal(s.lock, null);
  });

  it('layerAfterKey clears latch but keeps lock', () => {
    let s = createLayerState();
    s = toggleLayer(s, 'func'); // latch to func
    s = layerAfterKey(s);
    assert.equal(s.active, 'base');
    assert.equal(s.latch, null);

    // Now test with lock
    s = toggleLayer(s, 'func');
    s = toggleLayer(s, 'func'); // lock func
    s = layerAfterKey(s);
    assert.equal(s.active, 'func'); // stays on func
    assert.equal(s.lock, 'func');
  });

  it('layerAfterKey does nothing when no latch', () => {
    const s = layerAfterKey(createLayerState());
    assert.equal(s.active, 'base');
  });
});
