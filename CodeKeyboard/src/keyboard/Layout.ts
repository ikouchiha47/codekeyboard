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

/** A split ergonomic layout with two independent halves */
export interface SplitLayoutDef {
  name: string;
  split: true;
  left: KeySpec[][];
  right: KeySpec[][];
  /** Column stagger per side (in row-height units) */
  stagger: {left: number[]; right: number[]};
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
 * Sofle Choc v2 — true split ergonomic layout.
 * Two independent halves with column stagger, wide split gap, and thumb clusters.
 */
const SOFLE_STAGGER = {left: [0, 0.25, 0.5, 0.75, 1.0, 1.0], right: [1.0, 0.75, 0.5, 0.25, 0, 0, 0]};

function s(label: string, col: number, staggerArr: number[], opts?: Partial<KeySpec>): KeySpec {
  return {label, width: 1, stagger: staggerArr[col] ?? 0, ...opts};
}

export const LAYOUT_SOFLE: SplitLayoutDef = {
  name: 'Sofle Choc v2',
  split: true,
  stagger: SOFLE_STAGGER,
  left: [
    [s('Esc', 0, SOFLE_STAGGER.left, {action:'escape'}),
     s('1', 1, SOFLE_STAGGER.left, {shift:'!', fn:'F1'}),
     s('2', 2, SOFLE_STAGGER.left, {shift:'@', fn:'F2'}),
     s('3', 3, SOFLE_STAGGER.left, {shift:'#', fn:'F3'}),
     s('4', 4, SOFLE_STAGGER.left, {shift:'$', fn:'F4'}),
     s('5', 5, SOFLE_STAGGER.left, {shift:'%', fn:'F5'})],
    [s('Tab', 0, SOFLE_STAGGER.left, {action:'tab'}),
     s('q', 1, SOFLE_STAGGER.left), s('w', 2, SOFLE_STAGGER.left),
     s('e', 3, SOFLE_STAGGER.left), s('r', 4, SOFLE_STAGGER.left),
     s('t', 5, SOFLE_STAGGER.left)],
    [s('Caps', 0, SOFLE_STAGGER.left, {action:'caps'}),
     s('a', 1, SOFLE_STAGGER.left), s('s', 2, SOFLE_STAGGER.left),
     s('d', 3, SOFLE_STAGGER.left), s('f', 4, SOFLE_STAGGER.left),
     s('g', 5, SOFLE_STAGGER.left)],
    [s('Shift', 0, SOFLE_STAGGER.left, {action:'shift'}),
     s('z', 1, SOFLE_STAGGER.left), s('x', 2, SOFLE_STAGGER.left),
     s('c', 3, SOFLE_STAGGER.left), s('v', 4, SOFLE_STAGGER.left),
     s('b', 5, SOFLE_STAGGER.left)],
    [s('Ctrl', 0, SOFLE_STAGGER.left, {action:'ctrl'}),
     s('Alt', 1, SOFLE_STAGGER.left, {action:'alt'}),
     s('Cmd', 3, SOFLE_STAGGER.left, {action:'meta'}),
     s('Spc', 4, SOFLE_STAGGER.left, {action:'space'}),
     s('Spc', 5, SOFLE_STAGGER.left, {action:'space'})],
  ],
  right: [
    [s('6', 0, SOFLE_STAGGER.right, {shift:'^', fn:'F6'}),
     s('7', 1, SOFLE_STAGGER.right, {shift:'&', fn:'F7'}),
     s('8', 2, SOFLE_STAGGER.right, {shift:'*', fn:'F8'}),
     s('9', 3, SOFLE_STAGGER.right, {shift:'(', fn:'F9'}),
     s('0', 4, SOFLE_STAGGER.right, {shift:')', fn:'F10'}),
     s('-', 5, SOFLE_STAGGER.right, {shift:'_', fn:'F11'}),
     s('=', 6, SOFLE_STAGGER.right, {shift:'+', fn:'F12'})],
    [s('y', 0, SOFLE_STAGGER.right), s('u', 1, SOFLE_STAGGER.right),
     s('i', 2, SOFLE_STAGGER.right), s('o', 3, SOFLE_STAGGER.right),
     s('p', 4, SOFLE_STAGGER.right),
     s('[', 5, SOFLE_STAGGER.right, {shift:'{'}), s(']', 6, SOFLE_STAGGER.right, {shift:'}'})],
    [s('h', 0, SOFLE_STAGGER.right), s('j', 1, SOFLE_STAGGER.right),
     s('k', 2, SOFLE_STAGGER.right), s('l', 3, SOFLE_STAGGER.right),
     s(';', 4, SOFLE_STAGGER.right, {shift:':'}), s("'", 5, SOFLE_STAGGER.right, {shift:'"'}),
     s('Enter', 6, SOFLE_STAGGER.right, {action:'enter'})],
    [s('n', 0, SOFLE_STAGGER.right), s('m', 1, SOFLE_STAGGER.right),
     s(',', 2, SOFLE_STAGGER.right, {shift:'<'}), s('.', 3, SOFLE_STAGGER.right, {shift:'>'}),
     s('/', 4, SOFLE_STAGGER.right, {shift:'?'}),
     s('Shift', 5, SOFLE_STAGGER.right, {action:'shift'}),
     s('Bksp', 6, SOFLE_STAGGER.right, {action:'backspace'})],
    [s('Spc', 0, SOFLE_STAGGER.right, {action:'space'}),
     s('Spc', 1, SOFLE_STAGGER.right, {action:'space'}),
     s('Fn', 2, SOFLE_STAGGER.right, {action:'fn'}),
     s('Alt', 3, SOFLE_STAGGER.right, {action:'alt'}),
     s('Ctrl', 4, SOFLE_STAGGER.right, {action:'ctrl'}),
     s('←', 5, SOFLE_STAGGER.right, {action:'arrow-left'}),
     s('→', 6, SOFLE_STAGGER.right, {action:'arrow-right'})],
  ],
};

export const MODIFIERS = new Set(['ctrl','alt','shift','caps','fn']);

export function isModifier(a: string): boolean {
  return MODIFIERS.has(a);
}

export function isLetter(c: string): boolean {
  return /^[a-zA-Z]$/.test(c);
}
