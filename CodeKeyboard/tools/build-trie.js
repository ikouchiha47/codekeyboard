#!/usr/bin/env node
/**
 * Build a packed binary trie from a word list (one word per line).
 *
 * Binary format (little-endian):
 *   Header: 12 bytes
 *     magic: "TRIE2\0\0\0\0\0\0" (12 bytes)
 *     nodeCount at offset 8: u32 (4 bytes)
 *
 *   Nodes: nodeCount * 8 bytes each
 *     char: u8 (0 = root)
 *     flags: u8 (bit0 = isEnd, bit1 = hasChildren)
 *     childrenOffset: u32 (byte offset into child data section)
 *     dummy: u16 (padding)
 *
 *   Child data:
 *     For each node in order:
 *       childCount: u8
 *       entries: childCount * (childChar: u8 + childIndex: u16)
 *
 * Usage: cat wordlist.txt | node tools/build-trie.js > en.trie
 */

const readline = require('readline');

function buildTrie(words) {
  const trie = [{c: 0, end: false, children: {}}];

  for (const w of words) {
    const clean = w.trim().toLowerCase();
    if (!clean || !/^[a-z]+$/.test(clean) || clean.length <= 1) continue;
    let idx = 0;
    for (const ch of clean) {
      if (!trie[idx].children[ch]) {
        trie[idx].children[ch] = trie.length;
        trie.push({c: ch.charCodeAt(0), end: false, children: {}});
      }
      idx = trie[idx].children[ch];
    }
    trie[idx].end = true;
  }
  return trie;
}

function serialize(trie) {
  const nodeSize = 8;
  const nodeTable = Buffer.alloc(trie.length * nodeSize);
  const childParts = [];

  for (let i = 0; i < trie.length; i++) {
    const n = trie[i];
    const keys = Object.keys(n.children);
    const childCount = keys.length;

    let flags = 0;
    if (n.end) flags |= 1;
    if (childCount > 0) flags |= 2;

    const childrenOffset = childParts.reduce((a, b) => a + b.length, 0);

    const off = i * nodeSize;
    nodeTable.writeUInt8(n.c, off + 0);
    nodeTable.writeUInt8(flags, off + 1);
    nodeTable.writeUInt32LE(childrenOffset, off + 2);

    const entrySize = 3;
    const childBuf = Buffer.alloc(1 + childCount * entrySize + 1);
    childBuf.writeUInt8(childCount, 0);
    for (let j = 0; j < childCount; j++) {
      const base = 1 + j * entrySize;
      childBuf.writeUInt8(keys[j].charCodeAt(0), base);
      childBuf.writeUInt16LE(n.children[keys[j]], base + 1);
    }
    childParts.push(childBuf);
  }

  const childData = Buffer.concat(childParts);

  const header = Buffer.alloc(12);
  header.write('TRIE2', 0, 5, 'ascii');
  header.writeUInt32LE(trie.length, 8);

  return Buffer.concat([header, nodeTable, childData]);
}

if (require.main === module) {
  (async () => {
    const rl = readline.createInterface({input: process.stdin});
    const words = [];
    for await (const line of rl) {
      const w = line.trim().toLowerCase().split(/\s+/)[0];
      if (w && /^[a-z]+$/.test(w) && w.length >= 2 && w.length <= 12 && /[aeiouy]/.test(w)) {
        words.push(w);
      }
    }
    const trie = buildTrie(words);
    const buf = serialize(trie);
    process.stdout.write(buf);
  })();
}

module.exports = {buildTrie, serialize};
