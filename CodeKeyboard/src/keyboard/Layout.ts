export interface KeySpec {
  label: string;
  action?: string;
  shift?: string;
  alt?: string;
  fn?: string;
  width: number;
  /** Vertical stagger offset in row-height units (for column-staggered ergo layouts) */
  stagger?: number;
}

export interface LayoutDef {
  name: string;
  rows: KeySpec[][];
  /** Column index before which a split gap is rendered (for split ergo layouts) */
  splitAfter?: number;
}

export const LAYOUT_QWERTY: LayoutDef = {
  name: 'QWERTY',
  splitAfter: undefined,
  rows: [
    [
      {label:'Esc',  action:'escape', width:1},
      {label:'`',    shift:'~', width:1},
      {label:'1',    shift:'!',  alt:'α',  fn:'F1', width:1},
      {label:'2',    shift:'@',  alt:'β',  fn:'F2', width:1},
      {label:'3',    shift:'#',  alt:'γ',  fn:'F3', width:1},
      {label:'4',    shift:'$',  alt:'δ',  fn:'F4', width:1},
      {label:'5',    shift:'%',  alt:'ε',  fn:'F5', width:1},
      {label:'6',    shift:'^',  alt:'φ',  fn:'F6', width:1},
      {label:'7',    shift:'&',  alt:'η',  fn:'F7', width:1},
      {label:'8',    shift:'*',  alt:'θ',  fn:'F8', width:1},
      {label:'9',    shift:'(',  alt:'ι',  fn:'F9', width:1},
      {label:'0',    shift:')',  alt:'κ',  fn:'F10', width:1},
      {label:'-',    shift:'_',  alt:'–',  fn:'F11', width:1},
      {label:'=',    shift:'+',  alt:'≈',  fn:'F12', width:1},
      {label:'Bksp', action:'backspace', width:2},
    ],
    [
      {label:'Tab',  action:'tab', width:1.5},
      {label:'q', width:1},  {label:'w', width:1},  {label:'e', width:1},  {label:'r', width:1},
      {label:'t', width:1},  {label:'y', width:1},  {label:'u', width:1},  {label:'i', width:1},
      {label:'o', width:1},  {label:'p', width:1},
      {label:'[', shift:'{', width:1},  {label:']', shift:'}', width:1},
      {label:'\\', shift:'|', width:1.5},
    ],
    [
      {label:'Caps', action:'caps', width:1.75},
      {label:'a', width:1},  {label:'s', width:1},  {label:'d', width:1},  {label:'f', width:1},
      {label:'g', width:1},  {label:'h', width:1},  {label:'j', width:1},  {label:'k', width:1},
      {label:'l', width:1},
      {label:';', shift:':', width:1},  {label:"'", shift:'"', width:1},
      {label:'Enter', action:'enter', width:1.75},
    ],
    [
      {label:'Shift', action:'shift', width:2.5},
      {label:'z', width:1},  {label:'x', width:1},  {label:'c', width:1},  {label:'v', width:1},
      {label:'b', width:1},  {label:'n', width:1},  {label:'m', width:1},
      {label:',', shift:'<', alt:'≤', width:1},
      {label:'.', shift:'>', alt:'≥', width:1},
      {label:'/', shift:'?', alt:'÷', width:1},
      {label:'Shift', action:'shift', width:2.5},
    ],
    [
      {label:'Ctrl', action:'ctrl', width:1.5},
      {label:'Alt', action:'alt', width:1.25},
      {label:'Fn', action:'fn', width:1.25},
      {label:'Space', action:'space', width:5},
      {label:'Alt', action:'alt', width:1.25},
      {label:'Ctrl', action:'ctrl', width:1.5},
      {label:'←', action:'arrow-left', width:1},
      {label:'↑', action:'arrow-up', width:1},
      {label:'↓', action:'arrow-down', width:1},
      {label:'→', action:'arrow-right', width:1},
    ],
  ],
};

/**
 * Sofle v2-inspired column-staggered ergonomic layout.
 * Each column has a vertical offset to follow natural finger reach.
 * Visual split gap between left (cols 0-5) and right (cols 6+) halves.
 */
export const LAYOUT_SOFLE: LayoutDef = {
  name: 'Sofle v2',
  splitAfter: 6,
  rows: (() => {
    // Stagger offsets per column for all rows (in row-height units)
    const STAGGER = [0, 0.25, 0.5, 0.75, 1.0, 1.0, 1.0, 0.75, 0.5, 0.25, 0, 0, 0, 0];

    function s(label: string, col: number, opts?: Partial<KeySpec>): KeySpec {
      return {label, width: 1, stagger: STAGGER[col] ?? 0, ...opts};
    }

    return [
      // Row 0 — Numbers
      [
        s('Esc', 0, {action:'escape'}),
        s('`', 0, {shift:'~', width:0}),  // spacer under Esc
        s('1', 1, {shift:'!', alt:'α', fn:'F1'}),
        s('2', 2, {shift:'@', alt:'β', fn:'F2'}),
        s('3', 3, {shift:'#', alt:'γ', fn:'F3'}),
        s('4', 4, {shift:'$', alt:'δ', fn:'F4'}),
        s('5', 5, {shift:'%', alt:'ε', fn:'F5'}),
        s('6', 6, {shift:'^', alt:'ζ', fn:'F6'}),
        s('7', 7, {shift:'&', alt:'η', fn:'F7'}),
        s('8', 8, {shift:'*', alt:'θ', fn:'F8'}),
        s('9', 9, {shift:'(', alt:'ι', fn:'F9'}),
        s('0', 10, {shift:')', alt:'κ', fn:'F10'}),
        s('-', 11, {shift:'_', alt:'–', fn:'F11'}),
        s('=', 12, {shift:'+', alt:'≈', fn:'F12'}),
        s('Bksp', 13, {action:'backspace', width:1.5}),
      ],
      // Row 1 — Top alpha
      [
        s('Tab', 0, {action:'tab'}),
        s('q', 1), s('w', 2), s('e', 3), s('r', 4), s('t', 5),
        s('y', 6), s('u', 7), s('i', 8), s('o', 9), s('p', 10),
        s('[', 11, {shift:'{'}), s(']', 12, {shift:'}'}),
        s('\\', 13, {shift:'|'}),
      ],
      // Row 2 — Home row
      [
        s('Caps', 0, {action:'caps'}),
        s('a', 1), s('s', 2), s('d', 3), s('f', 4), s('g', 5),
        s('h', 6), s('j', 7), s('k', 8), s('l', 9),
        s(';', 10, {shift:':'}), s("'", 11, {shift:'"'}),
        s('Enter', 12, {action:'enter', width:1.5}),
        {label:'', width:0, stagger: 0}, // spacer
      ],
      // Row 3 — Bottom alpha
      [
        s('Shift', 0, {action:'shift'}),
        s('z', 1), s('x', 2), s('c', 3), s('v', 4), s('b', 5),
        s('n', 6), s('m', 7),
        s(',', 8, {shift:'<', alt:'≤'}),
        s('.', 9, {shift:'>', alt:'≥'}),
        s('/', 10, {shift:'?', alt:'÷'}),
        s('Shift', 11, {action:'shift', width:1.5}),
        {label:'', width:0, stagger: 0},
        {label:'', width:0, stagger: 0},
      ],
      // Row 4 — Thumb cluster + modifiers + arrows
      [
        s('Ctrl', 0, {action:'ctrl'}),
        s('Alt', 1, {action:'alt'}),
        s('Cmd', 2, {action:'meta'}),
        {label:'Space', action:'space', width:3, stagger: STAGGER[3] ?? 0},
        {label:'', width:0, stagger: 0}, // spacer
        {label:'', width:0, stagger: 0}, // spacer
        s('Fn', 6, {action:'fn'}),
        {label:'Space', action:'space', width:3, stagger: STAGGER[7] ?? 0},
        {label:'', width:0, stagger: 0}, // spacer
        {label:'', width:0, stagger: 0}, // spacer
        s('Alt', 10, {action:'alt'}),
        s('Ctrl', 11, {action:'ctrl'}),
        s('←', 12, {action:'arrow-left'}),
        s('↑', 13, {action:'arrow-up'}),
        s('↓', 14, {action:'arrow-down'}),
        s('→', 15, {action:'arrow-right'}),
      ],
    ];
  })(),
};

export const MODIFIERS = new Set(['ctrl','alt','shift','caps','fn']);

export function isModifier(a: string): boolean {
  return MODIFIERS.has(a);
}

export function isLetter(c: string): boolean {
  return /^[a-zA-Z]$/.test(c);
}
