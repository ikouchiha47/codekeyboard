#!/usr/bin/env node
/**
 * Build a packed binary trie from a frequency word list.
 *
 * Input: one "word<TAB>frequency" pair per line (frequency is a u32 integer).
 *        Lines with no tab are treated as frequency=1.
 *        Words are lowercased; non-alpha characters cause the line to be skipped.
 *
 * Binary format TRIF (little-endian):
 *
 *   Header: 12 bytes
 *     magic:     "TRIF\0" at offset 0  (5 bytes)
 *     padding:   3 bytes               (offsets 5-7)
 *     nodeCount: u32 LE                (offset 8)
 *
 *   Node array: nodeCount * 12 bytes each
 *     offset 0: char u8          (0 for root)
 *     offset 1: flags u8         (bit0=isEnd, bit1=hasChildren)
 *     offset 2: childrenOffset u32 LE  (byte offset into child data section)
 *     offset 6: frequency u32 LE      (raw count for terminal nodes, 0 otherwise)
 *     offset 10: reserved u16
 *
 *   Child data section (immediately after node array):
 *     For each node in BFS order:
 *       childCount: u8
 *       entries: childCount * 3 bytes (childChar: u8 + childIndex: u16 LE)
 *
 * Usage:
 *   python scripts/gen_wordlist.py | node tools/build-trie.js > android/app/src/main/assets/en.trie
 *   cat plain_wordlist.txt        | node tools/build-trie.js > en.trie
 */

const readline = require('readline');

function buildTrie(lines) {
  // Each node: { c, end, freq, children }
  const trie = [{ c: 0, end: false, freq: 0, children: {} }];

  for (const line of lines) {
    const tab = line.indexOf('\t');
    const word = (tab >= 0 ? line.slice(0, tab) : line).trim().toLowerCase();
    const freq = tab >= 0 ? (parseInt(line.slice(tab + 1), 10) || 1) : 1;

    if (!word || !/^[a-z]+$/.test(word) || word.length < 2 || word.length > 20) continue;

    let idx = 0;
    for (const ch of word) {
      if (!trie[idx].children[ch]) {
        trie[idx].children[ch] = trie.length;
        trie.push({ c: ch.charCodeAt(0), end: false, freq: 0, children: {} });
      }
      idx = trie[idx].children[ch];
    }
    trie[idx].end = true;
    trie[idx].freq = Math.max(trie[idx].freq, freq); // keep highest if duplicate
  }

  return trie;
}

function serialize(trie) {
  const NODE_SIZE = 12;
  const nodeTable = Buffer.alloc(trie.length * NODE_SIZE);
  const childParts = [];

  let childOffset = 0;

  for (let i = 0; i < trie.length; i++) {
    const n = trie[i];
    const keys = Object.keys(n.children).sort(); // sort for deterministic output
    const childCount = keys.length;

    let flags = 0;
    if (n.end)          flags |= 1;
    if (childCount > 0) flags |= 2;

    const off = i * NODE_SIZE;
    nodeTable.writeUInt8(n.c,           off + 0);
    nodeTable.writeUInt8(flags,         off + 1);
    nodeTable.writeUInt32LE(childOffset, off + 2);
    nodeTable.writeUInt32LE(n.end ? Math.min(n.freq, 0xFFFFFFFF) : 0, off + 6);
    nodeTable.writeUInt16LE(0,          off + 10); // reserved

    const ENTRY_SIZE = 4; // char:u8 + index:u24 LE
    const childBuf = Buffer.alloc(1 + childCount * ENTRY_SIZE);
    childBuf.writeUInt8(childCount, 0);
    for (let j = 0; j < childCount; j++) {
      const base = 1 + j * ENTRY_SIZE;
      childBuf.writeUInt8(keys[j].charCodeAt(0), base);
      const childIdx = n.children[keys[j]];
      if (childIdx > 0xFFFFFF) throw new Error(`child index ${childIdx} exceeds u24 — too many nodes`);
      childBuf.writeUInt8(childIdx & 0xFF,         base + 1);
      childBuf.writeUInt8((childIdx >> 8) & 0xFF,  base + 2);
      childBuf.writeUInt8((childIdx >> 16) & 0xFF, base + 3);
    }
    childParts.push(childBuf);
    childOffset += childBuf.length;
  }

  const childData = Buffer.concat(childParts);

  const header = Buffer.alloc(12);
  header.write('TRIF', 0, 4, 'ascii');
  header.writeUInt32LE(trie.length, 8);

  const out = Buffer.concat([header, nodeTable, childData]);
  process.stderr.write(
    `nodes=${trie.length}  size=${(out.length / 1024).toFixed(1)}KB\n`
  );
  return out;
}

if (require.main === module) {
  (async () => {
    const rl = readline.createInterface({ input: process.stdin });
    const lines = [];
    for await (const line of rl) lines.push(line);
    const trie = buildTrie(lines);
    process.stdout.write(serialize(trie));
  })();
}

module.exports = { buildTrie, serialize };
