const HEADER_SIZE = 12;
const NODE_SIZE = 8;

export class Trie {
  private view: DataView;

  constructor(data: ArrayBufferLike | Uint8Array) {
    if (data instanceof Uint8Array) {
      this.view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    } else {
      this.view = new DataView(data);
    }
    const magic = String.fromCharCode(
      this.view.getUint8(0),
      this.view.getUint8(1),
      this.view.getUint8(2),
      this.view.getUint8(3),
      this.view.getUint8(4),
    );
    if (magic !== 'TRIE2') throw new Error('Invalid trie magic');
  }

  private nodeCount(): number {
    return this.view.getUint32(8, true);
  }

  private readU8(offset: number): number {
    return this.view.getUint8(offset);
  }

  private readU16LE(offset: number): number {
    return this.view.getUint16(offset, true);
  }

  private readU32LE(offset: number): number {
    return this.view.getUint32(offset, true);
  }

  private nodeFlags(nodeIndex: number): number {
    return this.readU8(HEADER_SIZE + nodeIndex * NODE_SIZE + 1);
  }

  private isEnd(nodeIndex: number): boolean {
    return (this.nodeFlags(nodeIndex) & 1) !== 0;
  }

  private hasChildren(nodeIndex: number): boolean {
    return (this.nodeFlags(nodeIndex) & 2) !== 0;
  }

  private childrenOffset(nodeIndex: number): number {
    return this.readU32LE(HEADER_SIZE + nodeIndex * NODE_SIZE + 2);
  }

  private findChild(nodeIndex: number, char: string): number {
    if (!this.hasChildren(nodeIndex)) return -1;
    const offset = this.childrenOffset(nodeIndex);
    const childCount = this.readU8(offset);
    for (let i = 0; i < childCount; i++) {
      const base = offset + 1 + i * 3;
      if (this.readU8(base) === char.charCodeAt(0)) {
        return this.readU16LE(base + 1);
      }
    }
    return -1;
  }

  walk(prefix: string): number {
    let idx = 0;
    for (const ch of prefix) {
      idx = this.findChild(idx, ch);
      if (idx < 0) return -1;
    }
    return idx;
  }

  collect(nodeIndex: number, prefix: string, max: number): string[] {
    const results: string[] = [];
    const stack: Array<{idx: number; suffix: string}> = [
      {idx: nodeIndex, suffix: ''},
    ];

    while (stack.length > 0 && results.length < max) {
      const {idx, suffix} = stack.pop()!;
      if (this.isEnd(idx) && suffix.length > 0) {
        results.push(prefix + suffix);
      }
      if (this.hasChildren(idx)) {
        const offset = this.childrenOffset(idx);
        const childCount = this.readU8(offset);
        for (let i = childCount - 1; i >= 0; i--) {
          const base = offset + 1 + i * 3;
          const ch = String.fromCharCode(this.readU8(base));
          const childIdx = this.readU16LE(base + 1);
          stack.push({idx: childIdx, suffix: suffix + ch});
        }
      }
    }

    return results;
  }

  suggest(prefix: string, max: number = 3): string[] {
    if (!prefix) return [];
    const p = prefix.toLowerCase();
    const nodeIdx = this.walk(p);
    if (nodeIdx < 0) return [];
    return this.collect(nodeIdx, p, max);
  }

  has(prefix: string): boolean {
    if (!prefix) return false;
    return this.walk(prefix.toLowerCase()) >= 0;
  }
}
