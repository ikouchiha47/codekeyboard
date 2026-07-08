import {TRIE} from './DictionaryTrieData';
import {Trie} from './Trie';

const trie = new Trie(TRIE.buffer.slice(TRIE.byteOffset, TRIE.byteOffset + TRIE.byteLength));

export function getSuggestions(prefix: string, max: number = 3): string[] {
  return trie.suggest(prefix, max);
}

export function isValidWord(word: string): boolean {
  return trie.has(word);
}
