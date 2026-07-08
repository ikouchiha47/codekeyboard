const {describe, it} = require('node:test');
const assert = require('node:assert');
const {buildTrie, serialize} = require('../tools/build-trie');

describe('buildTrie', () => {
  it('builds a trie from words', () => {
    const trie = buildTrie(['hello', 'help', 'world']);
    assert.ok(trie.length >= 3);
    assert.equal(trie[0].c, 0);
    assert.equal(trie[0].end, false);
  });

  it('marks end nodes correctly', () => {
    const trie = buildTrie(['hello']);
    let idx = 0;
    for (const ch of 'hello') idx = trie[idx].children[ch];
    assert.equal(trie[idx].end, true);
    // intermediate 'h' should not be end
    const hIdx = trie[0].children['h'];
    assert.notEqual(hIdx, undefined);
    assert.equal(trie[hIdx].end, false);
  });

  it('handles overlapping prefixes', () => {
    const trie = buildTrie(['test', 'testing', 'tester']);
    let idx = 0;
    for (const ch of 'test') idx = trie[idx].children[ch];
    assert.equal(trie[idx].end, true);

    const i2 = trie[idx].children['i'];
    assert.notEqual(i2, undefined);
    assert.equal(trie[i2].end, false);

    let idx3 = i2;
    for (const ch of 'ng') idx3 = trie[idx3].children[ch];
    assert.equal(trie[idx3].end, true);
  });

  it('filters out single-char words', () => {
    const trie = buildTrie(['a', 'an', 'the']);
    // 'a' exists as intermediate node for 'an' but is NOT marked as end
    let idx = 0;
    for (const ch of 'a') idx = trie[idx].children[ch];
    assert.equal(trie[idx].end, false);
    assert.notEqual(trie[0].children['t'], undefined);
  });

  it('filters words with apostrophes', () => {
    const trie = buildTrie(["don't", 'hello']);
    assert.equal(trie[0].children['d'], undefined);
    assert.notEqual(trie[0].children['h'], undefined);
  });
});

describe('serialize', () => {
  it('produces valid header', () => {
    const buf = serialize(buildTrie(['hello', 'world']));
    assert.equal(buf.toString('ascii', 0, 5), 'TRIE2');
    const count = buf.readUInt32LE(8);
    assert.ok(count > 0);
  });

  it('roundtrip: serialize then query works', () => {
    const words = ['hello', 'help', 'world', 'he', 'hero'];
    const trie = buildTrie(words);
    const buf = serialize(trie);

    const HEADER = 12;
    const nodeCount = buf.readUInt32LE(8);
    const childBase = HEADER + nodeCount * 8;

    function findChild(nodeIdx, ch) {
      const flags = buf.readUInt8(HEADER + nodeIdx * 8 + 1);
      if (!(flags & 2)) return -1;
      const relOff = buf.readUInt32LE(HEADER + nodeIdx * 8 + 2);
      const absOff = childBase + relOff;
      const cc = buf.readUInt8(absOff);
      const code = ch.charCodeAt(0);
      for (let i = 0; i < cc; i++) {
        const base = absOff + 1 + i * 3;
        if (buf.readUInt8(base) === code) return buf.readUInt16LE(base + 1);
      }
      return -1;
    }

    function walk(prefix) {
      let idx = 0;
      for (const ch of prefix) {
        idx = findChild(idx, ch);
        if (idx < 0) return -1;
      }
      return idx;
    }

    for (const w of words) {
      assert.notEqual(walk(w), -1, `word "${w}" should exist`);
    }
    assert.equal(walk('xyz'), -1, 'xyz should not exist');
  });
});
