import React, {useState, useCallback, useRef} from 'react';
import {View, TextInput, StyleSheet} from 'react-native';
import {
  KeyboardLayout, KeySpec, isModifier, isLetter, isLayerAction,
  LAYOUT_SOFLE, isSplit, SplitLayout, StandardLayout, StandardLayer,
  getLayer,
} from './Layout';
import {
  ModState, L, createModState, toggleMod, clearLatched, effective,
  LayerState, createLayerState, toggleLayer, layerAfterKey,
} from './ModifierState';
import {getSuggestions} from './Dictionary';
import {Key} from './Key';
import {SuggestionBar} from './SuggestionBar';

interface ActionCtx {
  text: string;
  selStart: number;
  mods: ModState;
  layer: LayerState;
  setText: (v: React.SetStateAction<string>) => void;
  setSelStart: (v: React.SetStateAction<number>) => void;
  setMods: (v: React.SetStateAction<ModState>) => void;
  setLayer: (v: React.SetStateAction<LayerState>) => void;
  setSuggestions: (v: React.SetStateAction<string[]>) => void;
  inputRef: React.RefObject<TextInput | null>;
  updateSuggestions: (val: string, pos: number) => void;
  insertText: (t: string) => void;
  moveCursor: (dir: number) => void;
}

type ActionHandler = (ctx: ActionCtx, spec: KeySpec) => void;

const ACTION_REGISTRY: Record<string, ActionHandler> = {
  backspace: ({text, selStart, setText, setSelStart, updateSuggestions}) => {
    if (selStart <= 0) return;
    setText(prev => {
      const next = prev.substring(0, selStart - 1) + prev.substring(selStart);
      setSelStart(selStart - 1);
      updateSuggestions(next, selStart - 1);
      return next;
    });
  },
  delete: ({text, selStart, setText, setSelStart, updateSuggestions}) => {
    if (selStart >= text.length) return;
    setText(prev => {
      const next = prev.substring(0, selStart) + prev.substring(selStart + 1);
      updateSuggestions(next, selStart);
      return next;
    });
  },
  enter: ({selStart, setText, setSelStart, updateSuggestions}) => {
    setText(prev => {
      const next = prev.substring(0, selStart) + '\n' + prev.substring(selStart);
      setSelStart(selStart + 1);
      updateSuggestions(next, selStart + 1);
      return next;
    });
  },
  tab: ({insertText}) => insertText('    '),
  space: ({insertText}) => insertText(' '),
  escape: ({inputRef}) => inputRef.current?.blur(),
  'arrow-left': ({moveCursor}) => moveCursor(-1),
  'arrow-right': ({moveCursor}) => moveCursor(1),
  'arrow-up': ({moveCursor}) => moveCursor(-10),
  'arrow-down': ({moveCursor}) => moveCursor(10),
};

interface Props {
  layout?: KeyboardLayout;
}

const ROW_HEIGHT = 48;
const STAGGER_UNIT = 0.25;

function renderRows(
  inputRows: KeySpec[][],
  prefix: string,
  stagger: number[] | undefined,
  mods: ModState,
  layer: LayerState,
  onPress: (spec: KeySpec) => void,
) {
  return inputRows.map((row, ri) => (
    <View key={prefix + ri} style={styles.row}>
      {row.filter(s => s.label !== '').map((spec, ci) => {
        const action = spec.action ?? '';
        const mod = action as keyof ModState;
        const isMod = isModifier(action);
        const isLayer = isLayerAction(action);
        const latched = (isMod && mods[mod] === L.LATCHED) || (isLayer && layer.latch === action);
        const locked = (isMod && mods[mod] === L.LOCKED) || (isLayer && layer.lock === action);
        const staggerPx = stagger ? (stagger[ci] ?? 0) * ROW_HEIGHT * STAGGER_UNIT : 0;
        return (
          <View key={prefix + ri + '-' + ci} style={[styles.keyWrap, {marginTop: staggerPx}]}>
            {spec.label !== '' && (
              <Key spec={spec} latched={latched} locked={locked} onPress={onPress} />
            )}
          </View>
        );
      })}
    </View>
  ));
}

export function Keyboard({layout = LAYOUT_SOFLE}: Props) {
  const [mods, setMods] = useState<ModState>(createModState());
  const [layer, setLayer] = useState<LayerState>(createLayerState());
  const [text, setText] = useState('');
  const [selStart, setSelStart] = useState(0);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const inputRef = useRef<TextInput>(null);

  const ctxRef = useRef<ActionCtx>(null!);

  const updateSuggestions = useCallback((val: string, pos: number) => {
    let start = pos;
    while (start > 0 && /[a-zA-Z0-9_]/.test(val[start - 1])) start--;
    const word = val.substring(start, pos).toLowerCase();
    setSuggestions(getSuggestions(word));
  }, []);

  const insertText = useCallback(
    (t: string) => {
      setText(prev => {
        const before = prev.substring(0, selStart);
        const after = prev.substring(selStart);
        const next = before + t + after;
        const newPos = selStart + t.length;
        setTimeout(() => inputRef.current?.focus(), 0);
        setSelStart(newPos);
        updateSuggestions(next, newPos);
        return next;
      });
    },
    [selStart, updateSuggestions],
  );

  const moveCursor = useCallback(
    (dir: number) => {
      setSelStart(prev => {
        const next = Math.max(0, Math.min(text.length, prev + dir));
        updateSuggestions(text, next);
        return next;
      });
    },
    [text, updateSuggestions],
  );

  const applySuggestion = useCallback(
    (word: string) => {
      setText(prev => {
        let start = selStart;
        while (start > 0 && /[a-zA-Z0-9_]/.test(prev[start - 1])) start--;
        const before = prev.substring(0, start);
        const after = prev.substring(selStart);
        const next = before + word + ' ' + after;
        const newPos = start + word.length + 1;
        setSelStart(newPos);
        updateSuggestions(next, newPos);
        return next;
      });
    },
    [selStart, updateSuggestions],
  );

  const comboActions: Record<string, () => void> = {
    a: () => inputRef.current?.focus(),
    c: () => {},
    v: () => {},
    x: () => {},
    z: () => {},
    s: () => {},
    d: () =>
      setText(prev => {
        let lineStart = prev.lastIndexOf('\n', selStart - 1) + 1;
        let lineEnd = prev.indexOf('\n', selStart);
        if (lineEnd === -1) lineEnd = prev.length;
        const line = prev.substring(lineStart, lineEnd);
        return prev.substring(0, lineEnd) + '\n' + line + prev.substring(lineEnd);
      }),
  };

  const ctx: ActionCtx = {
    text, selStart, mods, layer,
    setText, setSelStart, setMods, setLayer, setSuggestions,
    inputRef, updateSuggestions, insertText, moveCursor,
  };
  ctxRef.current = ctx;

  const handleKeyPress = useCallback((spec: KeySpec) => {
    const c = ctxRef.current;
    const action = spec.action ?? '';

    if (isModifier(action)) {
      c.setMods(prev => toggleMod(prev, action as keyof ModState));
      return;
    }

    if (isLayerAction(action)) {
      c.setLayer(prev => toggleLayer(prev, action));
      return;
    }

    const handler = ACTION_REGISTRY[action];
    if (handler) {
      handler(c, spec);
      c.setMods(prev => clearLatched(prev));
      c.setLayer(prev => layerAfterKey(prev));
      return;
    }

    const ef = effective(c.mods);

    if (ef.ctrl) {
      const fn = comboActions[spec.label.toLowerCase()];
      if (fn) fn();
      c.setMods(prev => clearLatched(prev));
      return;
    }

    let ch = spec.label;
    if (ef.fn && spec.fn) ch = spec.fn;
    else if (ef.alt && spec.alt) ch = spec.alt;
    else if (ef.shift && spec.shift) ch = spec.shift;

    if (isLetter(ch)) {
      const upper = (ef.shift && !ef.caps) || (!ef.shift && ef.caps);
      ch = upper ? ch.toUpperCase() : ch.toLowerCase();
    }

    c.insertText(ch);
    c.setMods(prev => clearLatched(prev));
    c.setLayer(prev => layerAfterKey(prev));
  }, []);

  const layerName = layer.active;
  const layerData = getLayer(layout, layerName);

  return (
    <View style={styles.container}>
      <View style={styles.outputArea}>
        <TextInput
          ref={inputRef}
          style={styles.input}
          value={text}
          onChangeText={setText}
          onSelectionChange={e => {
            const pos = e.nativeEvent.selection.start;
            setSelStart(pos);
            updateSuggestions(text, pos);
          }}
          placeholder="Type here..."
          placeholderTextColor="#555"
          autoCapitalize="none"
          autoCorrect={false}
          multiline
        />
      </View>
      <SuggestionBar suggestions={suggestions} onSelect={applySuggestion} />
      <View style={styles.keyboard}>
        {isSplit(layout) ? (
          <View style={styles.splitContainer}>
            <View style={styles.half}>
              {renderRows(
                (layerData as SplitLayout['layers'][string]).left,
                'L',
                (layout as SplitLayout).stagger.left,
                mods, layer, handleKeyPress,
              )}
            </View>
            <View style={styles.gap} />
            <View style={styles.half}>
              {renderRows(
                (layerData as SplitLayout['layers'][string]).right,
                'R',
                (layout as SplitLayout).stagger.right,
                mods, layer, handleKeyPress,
              )}
            </View>
          </View>
        ) : (
          renderRows(
            (layerData as StandardLayer).rows,
            '',
            undefined,
            mods, layer, handleKeyPress,
          )
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#111',
  },
  outputArea: {
    height: 100,
    margin: 6,
    borderRadius: 8,
    overflow: 'hidden',
  },
  input: {
    flex: 1,
    backgroundColor: '#1a1a1a',
    color: '#d4d4d4',
    borderWidth: 1,
    borderColor: '#333',
    borderRadius: 8,
    padding: 10,
    fontSize: 15,
    fontFamily: 'monospace',
    lineHeight: 22,
    textAlignVertical: 'top',
  },
  keyboard: {
    padding: 6,
    gap: 5,
    backgroundColor: '#1a1a1a',
    borderTopWidth: 1,
    borderTopColor: '#333',
  },
  splitContainer: {
    flexDirection: 'row',
  },
  half: {
    flex: 1,
  },
  gap: {
    width: 16,
  },
  row: {
    flexDirection: 'row',
    gap: 3,
  },
  keyWrap: {
    height: ROW_HEIGHT,
    flex: 1,
  },
});
