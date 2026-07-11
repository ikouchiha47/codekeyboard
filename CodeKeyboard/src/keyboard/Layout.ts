export interface KeySpec {
  label: string;
  action?: string;
  shift?: string;
  alt?: string;
  fn?: string;
  c?: string;
  width: number;
  stagger?: number;
}

export interface StandardLayer {
  rows: KeySpec[][];
}

export interface SplitLayer {
  left: KeySpec[][];
  right: KeySpec[][];
}

export interface StandardLayout {
  type: 'standard';
  name: string;
  layers: Record<string, StandardLayer>;
}

export interface SplitLayout {
  type: 'split';
  name: string;
  layers: Record<string, SplitLayer>;
  stagger: {left: number[]; right: number[]};
}

export type KeyboardLayout = StandardLayout | SplitLayout;

export const LAYER_NAMES = ['base', 'lower', 'raise', 'adj', 'func'] as const;
export type LayerName = (typeof LAYER_NAMES)[number];

export function isSplit(l: KeyboardLayout): l is SplitLayout {
  return l.type === 'split';
}

export function isModifier(a: string): boolean {
  return MODIFIERS.has(a);
}

export function isLayerAction(a: string): boolean {
  return a === 'lower' || a === 'raise' || a === 'adj' || a === 'func';
}

export function isLetter(c: string): boolean {
  return /^[a-zA-Z]$/.test(c);
}

export const MODIFIERS = new Set(['ctrl','alt','shift','caps','fn']);

// ---- Standard QWERTY ----

const LAYER_QWERTY_BASE: StandardLayer = {
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

export const LAYOUT_QWERTY: StandardLayout = {
  type: 'standard',
  name: 'QWERTY',
  layers: {base: LAYER_QWERTY_BASE},
};

// ---- Sofle Choc v2 helper ----

const SOFLE_STAGGER = {
  left: [0, 0.25, 0.5, 0.75, 1.0, 1.0],
  right: [1.0, 0.75, 0.5, 0.25, 0, 0, 0],
};

function k(label: string, col: number, stagger: number[], opts?: Partial<KeySpec>): KeySpec {
  return {label, width: 1, stagger: stagger[col] ?? 0, ...opts};
}

// ---- Sofle layer definitions ----

const SOFLE_TARGET: Record<string, string> = {
  Br:  'brightness',
  Vol: 'volume',
  Prev:'media-prev', Play:'media-play', Next:'media-next',
  BT:  'bt', WiFi:'wifi',
  Undo:'undo', Redo:'redo',
  Cut: 'cut', Copy:'copy', Paste:'paste',
  SelAll:'select-all',  Save:'save', Find:'find',
  Repl:'replace',  Cmnt:'comment', Dup:'duplicate',
  Fmt: 'format',
};

const THUMB_R = (stagger: number[]) => [
  k('RSE', 0, stagger, {action:'raise', c:'layer-key'}),
  k('Spc', 1, stagger, {action:'space', c:'thumb'}),
  k('Spc', 2, stagger, {action:'space', c:'thumb'}),
  k('ADJ', 3, stagger, {action:'adj', c:'layer-key'}),
  k('Fn',  4, stagger, {action:'func', c:'func'}),
  k('←',   5, stagger, {action:'arrow-left'}),
  k('→',   6, stagger, {action:'arrow-right'}),
];

const THUMB_L = (stagger: number[]) => [
  k('Ctrl', 0, stagger, {action:'ctrl', c:'thumb'}),
  k('Alt',  1, stagger, {action:'alt', c:'thumb'}),
  k('Spc',  2, stagger, {action:'space', c:'thumb'}),
  k('LWR',  3, stagger, {action:'lower', c:'layer-key'}),
  k('Cmd',  4, stagger, {}),
];

const THUMB_L_EMPTY = (stagger: number[]) => [
  k('Ctrl', 0, stagger, {action:'ctrl', c:'thumb'}),
  k('Alt',  1, stagger, {action:'alt', c:'thumb'}),
  k('Spc',  2, stagger, {action:'space', c:'thumb'}),
  k('LWR',  3, stagger, {action:'lower', c:'layer-key'}),
  k('',     4, stagger, {}),
];

const LEFT_THUMB = (base: boolean, stagger: number[]) => base ? THUMB_L(stagger) : THUMB_L_EMPTY(stagger);

const SOFLE_BASE: SplitLayer = {
  left: [
    [k('Tab', 0, SOFLE_STAGGER.left, {action:'tab'}),
     k('q', 1, SOFLE_STAGGER.left), k('w', 2, SOFLE_STAGGER.left),
     k('e', 3, SOFLE_STAGGER.left), k('r', 4, SOFLE_STAGGER.left),
     k('t', 5, SOFLE_STAGGER.left)],
    [k('Caps',0, SOFLE_STAGGER.left, {action:'caps'}),
     k('a', 1, SOFLE_STAGGER.left), k('s', 2, SOFLE_STAGGER.left),
     k('d', 3, SOFLE_STAGGER.left), k('f', 4, SOFLE_STAGGER.left),
     k('g', 5, SOFLE_STAGGER.left)],
    [k('Shift',0, SOFLE_STAGGER.left, {action:'shift'}),
     k('z', 1, SOFLE_STAGGER.left), k('x', 2, SOFLE_STAGGER.left),
     k('c', 3, SOFLE_STAGGER.left), k('v', 4, SOFLE_STAGGER.left),
     k('b', 5, SOFLE_STAGGER.left)],
    LEFT_THUMB(true, SOFLE_STAGGER.left),
  ],
  right: [
    [k('y', 0, SOFLE_STAGGER.right), k('u', 1, SOFLE_STAGGER.right),
     k('i', 2, SOFLE_STAGGER.right), k('o', 3, SOFLE_STAGGER.right),
     k('p', 4, SOFLE_STAGGER.right),
     k('[', 5, SOFLE_STAGGER.right, {shift:'{'}), k(']', 6, SOFLE_STAGGER.right, {shift:'}'})],
    [k('h', 0, SOFLE_STAGGER.right), k('j', 1, SOFLE_STAGGER.right),
     k('k', 2, SOFLE_STAGGER.right), k('l', 3, SOFLE_STAGGER.right),
     k(';', 4, SOFLE_STAGGER.right, {shift:':'}), k("'", 5, SOFLE_STAGGER.right, {shift:'"'}),
     k('Enter',6, SOFLE_STAGGER.right, {action:'enter'})],
    [k('n', 0, SOFLE_STAGGER.right), k('m', 1, SOFLE_STAGGER.right),
     k(',', 2, SOFLE_STAGGER.right, {shift:'<'}), k('.', 3, SOFLE_STAGGER.right, {shift:'>'}),
     k('/', 4, SOFLE_STAGGER.right, {shift:'?'}),
     k('Shift',5, SOFLE_STAGGER.right, {action:'shift'}),
     k('Bksp', 6, SOFLE_STAGGER.right, {action:'backspace'})],
    THUMB_R(SOFLE_STAGGER.right),
  ],
};

const SOFLE_LOWER: SplitLayer = {
  left: [
    [k('Esc',0, SOFLE_STAGGER.left, {action:'escape'}),
     k('1', 1, SOFLE_STAGGER.left, {shift:'!'}),
     k('2', 2, SOFLE_STAGGER.left, {shift:'@'}),
     k('3', 3, SOFLE_STAGGER.left, {shift:'#'}),
     k('4', 4, SOFLE_STAGGER.left, {shift:'$'}),
     k('5', 5, SOFLE_STAGGER.left, {shift:'%'})],
    [k('`', 0, SOFLE_STAGGER.left), k('-', 1, SOFLE_STAGGER.left),
     k('=', 2, SOFLE_STAGGER.left), k('[', 3, SOFLE_STAGGER.left),
     k(']', 4, SOFLE_STAGGER.left), k('\\',5, SOFLE_STAGGER.left)],
    [k('~', 0, SOFLE_STAGGER.left), k('_', 1, SOFLE_STAGGER.left),
     k('+', 2, SOFLE_STAGGER.left), k('{', 3, SOFLE_STAGGER.left),
     k('}', 4, SOFLE_STAGGER.left), k('|', 5, SOFLE_STAGGER.left)],
    LEFT_THUMB(false, SOFLE_STAGGER.left),
  ],
  right: [
    [k('6', 0, SOFLE_STAGGER.right, {shift:'^'}),
     k('7', 1, SOFLE_STAGGER.right, {shift:'&'}),
     k('8', 2, SOFLE_STAGGER.right, {shift:'*'}),
     k('9', 3, SOFLE_STAGGER.right, {shift:'('}),
     k('0', 4, SOFLE_STAGGER.right, {shift:')'}),
     k('-', 5, SOFLE_STAGGER.right), k('=', 6, SOFLE_STAGGER.right)],
    [k('^', 0, SOFLE_STAGGER.right), k('&', 1, SOFLE_STAGGER.right),
     k('*', 2, SOFLE_STAGGER.right), k('(', 3, SOFLE_STAGGER.right),
     k(')', 4, SOFLE_STAGGER.right),
     k('_', 5, SOFLE_STAGGER.right), k('+', 6, SOFLE_STAGGER.right)],
    [k('!', 0, SOFLE_STAGGER.right), k('@', 1, SOFLE_STAGGER.right),
     k('#', 2, SOFLE_STAGGER.right), k('$', 3, SOFLE_STAGGER.right),
     k('%', 4, SOFLE_STAGGER.right),
     k('Bksp',5, SOFLE_STAGGER.right, {action:'backspace'}),
     k('Del', 6, SOFLE_STAGGER.right, {action:'delete'})],
    THUMB_R(SOFLE_STAGGER.right),
  ],
};

const SOFLE_RAISE: SplitLayer = {
  left: [
    [k('F1', 0, SOFLE_STAGGER.left), k('F2',1, SOFLE_STAGGER.left),
     k('F3', 2, SOFLE_STAGGER.left), k('F4',3, SOFLE_STAGGER.left),
     k('F5', 4, SOFLE_STAGGER.left), k('F6',5, SOFLE_STAGGER.left)],
    [k('F7', 0, SOFLE_STAGGER.left), k('F8',1, SOFLE_STAGGER.left),
     k('F9', 2, SOFLE_STAGGER.left), k('F10',3, SOFLE_STAGGER.left),
     k('F11',4, SOFLE_STAGGER.left), k('F12',5, SOFLE_STAGGER.left)],
    [k('PrtSc',0, SOFLE_STAGGER.left), k('ScrLk',1, SOFLE_STAGGER.left),
     k('Pause',2, SOFLE_STAGGER.left), k('Ins',3, SOFLE_STAGGER.left),
     k('Home',4, SOFLE_STAGGER.left), k('PgUp',5, SOFLE_STAGGER.left)],
    LEFT_THUMB(false, SOFLE_STAGGER.left),
  ],
  right: [
    [k('←', 0, SOFLE_STAGGER.right, {action:'arrow-left'}),
     k('↓', 1, SOFLE_STAGGER.right, {action:'arrow-down'}),
     k('↑', 2, SOFLE_STAGGER.right, {action:'arrow-up'}),
     k('→', 3, SOFLE_STAGGER.right, {action:'arrow-right'}),
     k('',  4, SOFLE_STAGGER.right), k('',5, SOFLE_STAGGER.right), k('',6, SOFLE_STAGGER.right)],
    [k('Home',0, SOFLE_STAGGER.right), k('End',1, SOFLE_STAGGER.right),
     k('PgUp',2, SOFLE_STAGGER.right), k('PgDn',3, SOFLE_STAGGER.right),
     k('',    4, SOFLE_STAGGER.right), k('',5, SOFLE_STAGGER.right), k('',6, SOFLE_STAGGER.right)],
    [k('Cut', 0, SOFLE_STAGGER.right, {action:'cut'}),
     k('Copy',1, SOFLE_STAGGER.right, {action:'copy'}),
     k('Paste',2, SOFLE_STAGGER.right, {action:'paste'}),
     k('',    3, SOFLE_STAGGER.right), k('',4, SOFLE_STAGGER.right),
     k('Del', 5, SOFLE_STAGGER.right, {action:'delete'}),
     k('Bksp',6, SOFLE_STAGGER.right, {action:'backspace'})],
    THUMB_R(SOFLE_STAGGER.right),
  ],
};

const SOFLE_ADJ: SplitLayer = {
  left: [
    [k('Esc',0, SOFLE_STAGGER.left, {action:'escape'}),
     k('Br-',1, SOFLE_STAGGER.left, {action:'brightness-down'}),
     k('Br+',2, SOFLE_STAGGER.left, {action:'brightness-up'}),
     k('Mute',3, SOFLE_STAGGER.left, {action:'volume-mute'}),
     k('Vol-',4, SOFLE_STAGGER.left, {action:'volume-down'}),
     k('Vol+',5, SOFLE_STAGGER.left, {action:'volume-up'})],
    [k('Prev',0, SOFLE_STAGGER.left, {action:'media-prev'}),
     k('Play',1, SOFLE_STAGGER.left, {action:'media-play'}),
     k('Next',2, SOFLE_STAGGER.left, {action:'media-next'}),
     k('',   3, SOFLE_STAGGER.left), k('',4, SOFLE_STAGGER.left), k('',5, SOFLE_STAGGER.left)],
    [k('', 0, SOFLE_STAGGER.left), k('',1, SOFLE_STAGGER.left),
     k('', 2, SOFLE_STAGGER.left), k('',3, SOFLE_STAGGER.left),
     k('', 4, SOFLE_STAGGER.left), k('',5, SOFLE_STAGGER.left)],
    LEFT_THUMB(false, SOFLE_STAGGER.left),
  ],
  right: [
    [k('', 0, SOFLE_STAGGER.right), k('',1, SOFLE_STAGGER.right),
     k('', 2, SOFLE_STAGGER.right), k('',3, SOFLE_STAGGER.right),
     k('', 4, SOFLE_STAGGER.right), k('',5, SOFLE_STAGGER.right), k('',6, SOFLE_STAGGER.right)],
    [k('BT',0, SOFLE_STAGGER.right, {action:'bt'}),
     k('WiFi',1, SOFLE_STAGGER.right, {action:'wifi'}),
     k('',  2, SOFLE_STAGGER.right), k('',3, SOFLE_STAGGER.right),
     k('',  4, SOFLE_STAGGER.right), k('',5, SOFLE_STAGGER.right), k('',6, SOFLE_STAGGER.right)],
    [k('', 0, SOFLE_STAGGER.right), k('',1, SOFLE_STAGGER.right),
     k('', 2, SOFLE_STAGGER.right), k('',3, SOFLE_STAGGER.right),
     k('', 4, SOFLE_STAGGER.right),
     k('Bksp',5, SOFLE_STAGGER.right, {action:'backspace'}),
     k('Del', 6, SOFLE_STAGGER.right, {action:'delete'})],
    THUMB_R(SOFLE_STAGGER.right),
  ],
};

const SOFLE_FUNC: SplitLayer = {
  left: [
    [k('Undo',0, SOFLE_STAGGER.left, {action:'undo'}),
     k('Redo',1, SOFLE_STAGGER.left, {action:'redo'}),
     k('Cut', 2, SOFLE_STAGGER.left, {action:'cut'}),
     k('Copy',3, SOFLE_STAGGER.left, {action:'copy'}),
     k('Paste',4, SOFLE_STAGGER.left, {action:'paste'}),
     k('SelAll',5, SOFLE_STAGGER.left, {action:'select-all'})],
    [k('Save',0, SOFLE_STAGGER.left, {action:'save'}),
     k('Find',1, SOFLE_STAGGER.left, {action:'find'}),
     k('Repl',2, SOFLE_STAGGER.left, {action:'replace'}),
     k('Cmnt',3, SOFLE_STAGGER.left, {action:'comment'}),
     k('Dup', 4, SOFLE_STAGGER.left, {action:'duplicate'}),
     k('Fmt', 5, SOFLE_STAGGER.left, {action:'format'})],
    [k('Tab',0, SOFLE_STAGGER.left, {action:'tab'}),
     k('Spc',1, SOFLE_STAGGER.left, {action:'space'}),
     k('Ent',2, SOFLE_STAGGER.left, {action:'enter'}),
     k('Esc',3, SOFLE_STAGGER.left, {action:'escape'}),
     k('',   4, SOFLE_STAGGER.left), k('',5, SOFLE_STAGGER.left)],
    LEFT_THUMB(false, SOFLE_STAGGER.left),
  ],
  right: [
    [k('', 0, SOFLE_STAGGER.right), k('',1, SOFLE_STAGGER.right),
     k('', 2, SOFLE_STAGGER.right), k('',3, SOFLE_STAGGER.right),
     k('', 4, SOFLE_STAGGER.right), k('',5, SOFLE_STAGGER.right), k('',6, SOFLE_STAGGER.right)],
    [k('', 0, SOFLE_STAGGER.right), k('',1, SOFLE_STAGGER.right),
     k('', 2, SOFLE_STAGGER.right), k('',3, SOFLE_STAGGER.right),
     k('', 4, SOFLE_STAGGER.right), k('',5, SOFLE_STAGGER.right), k('',6, SOFLE_STAGGER.right)],
    [k('', 0, SOFLE_STAGGER.right), k('',1, SOFLE_STAGGER.right),
     k('', 2, SOFLE_STAGGER.right), k('',3, SOFLE_STAGGER.right),
     k('', 4, SOFLE_STAGGER.right),
     k('Bksp',5, SOFLE_STAGGER.right, {action:'backspace'}),
     k('Del', 6, SOFLE_STAGGER.right, {action:'delete'})],
    THUMB_R(SOFLE_STAGGER.right),
  ],
};

export const LAYOUT_SOFLE: SplitLayout = {
  type: 'split',
  name: 'Sofle Choc v2',
  stagger: SOFLE_STAGGER,
  layers: {
    base: SOFLE_BASE,
    lower: SOFLE_LOWER,
    raise: SOFLE_RAISE,
    adj: SOFLE_ADJ,
    func: SOFLE_FUNC,
  },
};

// Pluggable layout registry – add new layouts here
export const LAYOUTS: Record<string, KeyboardLayout> = {
  qwerty: LAYOUT_QWERTY,
  sofle: LAYOUT_SOFLE,
};

export function getLayer(layout: KeyboardLayout, name: string): StandardLayer | SplitLayer {
  const l = layout.layers[name];
  if (!l) throw new Error(`Unknown layer "${name}" in layout "${layout.name}"`);
  return l;
}

/** Convert a native KeyDef (from Kotlin exportLayout JSON) to RN KeySpec. */
function nativeKeyToSpec(k: any): KeySpec {
  return {
    label: k.label ?? '',
    action: k.action ?? undefined,
    shift: k.shift ?? undefined,
    holdAction: k.holdAction ?? undefined,
    width: k.width ?? 1,
  };
}

/**
 * Build a SplitLayout from the JSON string returned by
 * `NativeModules.CodeKeyboardModule.getLayout()`.
 * Merges the native topRow into left/right halves (5 cols left, 5 cols right).
 */
export function buildFromNativeJson(json: string): SplitLayout {
  const data = JSON.parse(json);
  const layers: Record<string, SplitLayer> = {};

  for (const [name, ld] of Object.entries(data.layers)) {
    const layer = ld as {topRow: any[]; left: any[][]; right: any[][]};

    const rightPad = 5 - layer.topRow.slice(5).length;
    const rightTop = [
      ...layer.topRow.slice(5).map((k: any) => nativeKeyToSpec(k)),
      ...Array.from({length: Math.max(0, rightPad)}, () => ({label: '', width: 1})),
    ];

    layers[name] = {
      left: [
        layer.topRow.slice(0, 5).map((k: any) => nativeKeyToSpec(k)),
        ...layer.left.map((row: any[]) => row.map((k: any) => nativeKeyToSpec(k))),
      ],
      right: [
        rightTop,
        ...layer.right.map((row: any[]) => row.map((k: any) => nativeKeyToSpec(k))),
      ],
    };
  }

  return {
    type: 'split',
    name: 'Sofle V5',
    stagger: data.stagger,
    layers,
  };
}


