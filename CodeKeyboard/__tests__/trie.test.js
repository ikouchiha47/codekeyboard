const {describe, it} = require('node:test');
const assert = require('node:assert');
const {buildTrie, serialize} = require('../tools/build-trie');

const HEADER = 12;

function nodeOff(i) { return HEADER + i * 8; }

class TrieReader {
  constructor(buf) {
    this.buf = buf;
    const magic = buf.toString('ascii', 0, 5);
    if (magic !== 'TRIE2') throw new Error('Bad magic');
    this.nodeCount = buf.readUInt32LE(8);
    this.childBase = HEADER + this.nodeCount * 8;
  }

  findChild(nodeIdx, ch) {
    const flags = this.buf.readUInt8(nodeOff(nodeIdx) + 1);
    if (!(flags & 2)) return -1;
    const relOff = this.buf.readUInt32LE(nodeOff(nodeIdx) + 2);
    const absOff = this.childBase + relOff;
    const count = this.buf.readUInt8(absOff);
    const code = ch.charCodeAt(0);
    for (let i = 0; i < count; i++) {
      const base = absOff + 1 + i * 3;
      if (this.buf.readUInt8(base) === code) return this.buf.readUInt16LE(base + 1);
    }
    return -1;
  }

  walk(prefix) {
    let idx = 0;
    for (const ch of prefix) {
      idx = this.findChild(idx, ch);
      if (idx < 0) return -1;
    }
    return idx;
  }

  isEnd(nodeIdx) {
    return (this.buf.readUInt8(nodeOff(nodeIdx) + 1) & 1) !== 0;
  }

  has(prefix) { return this.walk(prefix) >= 0; }

  children(nodeIdx) {
    const flags = this.buf.readUInt8(nodeOff(nodeIdx) + 1);
    if (!(flags & 2)) return [];
    const relOff = this.buf.readUInt32LE(nodeOff(nodeIdx) + 2);
    const absOff = this.childBase + relOff;
    const count = this.buf.readUInt8(absOff);
    const kids = [];
    for (let i = 0; i < count; i++) {
      const base = absOff + 1 + i * 3;
      kids.push({
        ch: String.fromCharCode(this.buf.readUInt8(base)),
        idx: this.buf.readUInt16LE(base + 1),
      });
    }
    return kids;
  }

  suggest(prefix, max = 3) {
    const nodeIdx = this.walk(prefix);
    if (nodeIdx < 0) return [];

    const results = [];
    const stack = [{idx: nodeIdx, suffix: ''}];
    while (stack.length > 0 && results.length < max) {
      const {idx, suffix} = stack.pop();
      if (this.isEnd(idx) && suffix.length > 0) results.push(prefix + suffix);
      const kids = this.children(idx);
      for (let i = kids.length - 1; i >= 0; i--)
        stack.push({idx: kids[i].idx, suffix: suffix + kids[i].ch});
    }
    return results;
  }
}

describe('Trie serialization and query', () => {
  it('stores and retrieves words', () => {
    const buf = serialize(buildTrie(['hello', 'help', 'world', 'he']));
    const trie = new TrieReader(buf);
    assert.ok(trie.has('hello'));
    assert.ok(trie.has('help'));
    assert.ok(trie.has('world'));
    assert.ok(trie.has('he'));
  });

  it('returns false for absent words', () => {
    const buf = serialize(buildTrie(['cat', 'car']));
    const trie = new TrieReader(buf);
    assert.equal(trie.has('xyz'), false);
    // 'ca' is a prefix that exists but not a complete word
    assert.equal(trie.has('ca'), true); // walk succeeds for prefix
    assert.equal(trie.isEnd(trie.walk('ca')), false); // but not a word
  });

  it('suggests completions excluding the prefix itself', () => {
    const buf = serialize(buildTrie(['help', 'hello', 'helmet', 'hero', 'he']));
    const trie = new TrieReader(buf);
    const s = trie.suggest('he');
    assert.ok(s.length > 0);
    assert.equal(s.includes('he'), false);
  });

  it('respects max results', () => {
    const buf = serialize(buildTrie(['test', 'testing', 'tester', 'tested', 'testify']));
    const trie = new TrieReader(buf);
    assert.equal(trie.suggest('test', 1).length, 1);
    assert.equal(trie.suggest('test', 3).length, 3);
    assert.equal(trie.suggest('test', 10).length, 4);
  });

  it('returns empty for unknown prefix', () => {
    const buf = serialize(buildTrie(['hello', 'world']));
    const trie = new TrieReader(buf);
    assert.deepEqual(trie.suggest('xyz'), []);
  });

  it('handles empty trie', () => {
    const buf = serialize(buildTrie([]));
    const trie = new TrieReader(buf);
    assert.equal(trie.nodeCount, 1);
    assert.equal(trie.has('a'), false);
    assert.deepEqual(trie.suggest('a'), []);
  });

  it('suggestions include all completions', () => {
    const buf = serialize(buildTrie(['hello', 'help', 'helicopter', 'hell']));
    const trie = new TrieReader(buf);
    const s = trie.suggest('he', 10);
    assert.ok(s.includes('hello'));
    assert.ok(s.includes('help'));
    assert.ok(s.includes('helicopter'));
    assert.ok(s.includes('hell'));
  });

  it('hasChildren flag is accurate', () => {
    const buf = serialize(buildTrie(['cat', 'car']));
    const trie = new TrieReader(buf);

    // Root children: 'c'
    const rootKids = trie.children(0);
    assert.equal(rootKids.length, 1);
    assert.equal(rootKids[0].ch, 'c');

    // 'c' node should have children ('a')
    const cKids = trie.children(rootKids[0].idx);
    assert.equal(cKids.length, 1);
    assert.equal(cKids[0].ch, 'a');

    // 'ca' node should have children ('r', 't')
    const caKids = trie.children(cKids[0].idx);
    assert.equal(caKids.length, 2);
  });

  it('filtered single-char words are not end nodes', () => {
    const buf = serialize(buildTrie(['a', 'an', 'the']));
    const trie = new TrieReader(buf);
    // 'a' exists as intermediate node (from 'an') but is not an end node
    const aIdx = trie.walk('a');
    assert.notEqual(aIdx, -1);
    assert.equal(trie.isEnd(aIdx), false);
    assert.ok(trie.has('an'));
  });
});
