#!/usr/bin/env node
/**
 * Build a serialised trie file from a plain-text word list.
 *
 * Usage: node tools/build-trie.js < words.txt > en.trie
 *
 * The trie is a flat array of nodes, serialised as JSON.
 * Each node: { c: char, children: { [char]: nodeIndex }, end: bool }
 *
 * lookup(prefix) -> DFS collects up to `max` completions.
 */

const readline = require('readline');

function buildTrie(words) {
  const trie = [{c: null, children: {}, end: false}];

  for (const w of words) {
    let idx = 0;
    for (const ch of w.toLowerCase()) {
      if (trie[idx].children[ch] === undefined) {
        trie[idx].children[ch] = trie.length;
        trie.push({c: ch, children: {}, end: false});
      }
      idx = trie[idx].children[ch];
    }
    trie[idx].end = true;
  }
  return trie;
}

const rl = readline.createInterface({input: process.stdin});
const words = [];
rl.on('line', line => {
  const w = line.trim();
  if (w && !w.includes("'") && !w.includes('-') && w.length > 1) {
    words.push(w);
  }
});
rl.on('close', () => {
  const trie = buildTrie(words);
  process.stdout.write(JSON.stringify(trie));
});
