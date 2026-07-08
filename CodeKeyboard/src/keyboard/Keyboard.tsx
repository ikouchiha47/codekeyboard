import React, {useState, useCallback, useRef} from 'react';
import {View, TextInput, StyleSheet} from 'react-native';
import {LayoutDef, KeySpec, isModifier, isLetter, LAYOUT_QWERTY, LAYOUT_SOFLE} from './Layout';
import {ModState, L, createModState, toggleMod, clearLatched, effective} from './ModifierState';
import {getSuggestions} from './Dictionary';
import {Key} from './Key';
import {SuggestionBar} from './SuggestionBar';

interface Props {
  layout?: LayoutDef;
}

export function Keyboard({layout = LAYOUT_SOFLE}: Props) {
  const [mods, setMods] = useState<ModState>(createModState());
  const [text, setText] = useState('');
  const [selStart, setSelStart] = useState(0);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const inputRef = useRef<TextInput>(null);

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
        setTimeout(() => {
          inputRef.current?.focus();
        }, 0);
        setSelStart(newPos);
        updateSuggestions(next, newPos);
        return next;
      });
    },
    [selStart, updateSuggestions],
  );

  const backspace = useCallback(() => {
    setText(prev => {
      if (selStart <= 0) return prev;
      const before = prev.substring(0, selStart - 1);
      const after = prev.substring(selStart);
      const next = before + after;
      setSelStart(selStart - 1);
      updateSuggestions(next, selStart - 1);
      return next;
    });
  }, [selStart, updateSuggestions]);

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

  const handleKeyPress = useCallback(
    (spec: KeySpec) => {
      const action = spec.action ?? '';

      if (isModifier(action)) {
        setMods(prev => toggleMod(prev, action as keyof ModState));
        return;
      }

      const ef = effective(mods);

      if (action === 'backspace') { backspace(); return; }
      if (action === 'enter') { insertText('\n'); return; }
      if (action === 'tab') { insertText('    '); return; }
      if (action === 'space') { insertText(' '); return; }
      if (action === 'escape') { inputRef.current?.blur(); return; }
      if (action === 'arrow-left') { moveCursor(-1); return; }
      if (action === 'arrow-right') { moveCursor(1); return; }
      if (action === 'arrow-up') { moveCursor(-10); return; }
      if (action === 'arrow-down') { moveCursor(10); return; }

      if (ef.ctrl) {
        const fn = comboActions[spec.label.toLowerCase()];
        if (fn) fn();
        setMods(prev => clearLatched(prev));
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

      insertText(ch);
      setMods(prev => clearLatched(prev));
    },
    [mods, insertText, backspace, moveCursor],
  );

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
        {layout.rows.map((row, ri) => (
          <View key={ri} style={styles.row}>
            {row.map((spec, ki) => {
              if (!spec.width) return null;
              const a = spec.action ?? '';
              const isMod = isModifier(a);
              return (
                <View
                  key={`${ri}-${ki}`}
                  style={[
                    styles.keyWrap,
                    {flex: spec.width, marginTop: (spec.stagger ?? 0) * 48},
                    layout.splitAfter && ki === layout.splitAfter
                      ? {marginLeft: 12}
                      : null,
                  ]}>
                  <Key
                    spec={spec}
                    latched={isMod && mods[a as keyof ModState] === L.LATCHED}
                    locked={isMod && mods[a as keyof ModState] === L.LOCKED}
                    onPress={handleKeyPress}
                  />
                </View>
              );
            })}
          </View>
        ))}
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
  row: {
    flexDirection: 'row',
    gap: 4,
  },
  keyWrap: {
    height: 48,
  },
});
