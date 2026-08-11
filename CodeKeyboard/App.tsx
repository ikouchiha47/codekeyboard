import React, {useState, useCallback, useEffect} from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  StatusBar,
  ScrollView,
  useColorScheme,
  NativeModules,
} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
type Tab = 'settings' | 'themes' | 'layouts' | 'languages';

function SnippetsEditor() {
  const [snippets, setSnippets] = useState<Record<string, string>>({});
  const [newKey, setNewKey] = useState('');
  const [newVal, setNewVal] = useState('');
  const [addError, setAddError] = useState('');
  const [editErrors, setEditErrors] = useState<Record<string, string>>({});

  const loadKeys = useCallback(() => {
    NativeModules.SettingsModule?.getSnippetKeys((keys: string[]) => {
      const next: Record<string, string> = {};
      let pending = keys.length;
      if (pending === 0) { setSnippets({}); return; }
      keys.forEach(k => {
        NativeModules.SettingsModule?.getString(`snippet_${k}`, (val: string) => {
          next[k] = val ?? '';
          pending -= 1;
          if (pending === 0) setSnippets({...next});
        });
      });
    });
  }, []);

  useEffect(() => { loadKeys(); }, [loadKeys]);

  const handleAdd = useCallback(() => {
    const key = newKey.trim();
    const val = newVal.trim();
    if (!key) { setAddError('Shortcode cannot be empty'); return; }
    if (!/^[a-z0-9_]+$/.test(key)) { setAddError('Shortcode: lowercase letters, digits, underscore only'); return; }
    if (!val) { setAddError('Expansion cannot be empty'); return; }
    if (snippets.hasOwnProperty(key)) { setAddError(`';${key}' already exists`); return; }
    NativeModules.SettingsModule?.setString(`snippet_${key}`, val);
    setNewKey('');
    setNewVal('');
    setAddError('');
    loadKeys();
  }, [newKey, newVal, snippets, loadKeys]);

  const handleUpdate = useCallback((key: string, val: string) => {
    if (!val.trim()) {
      setEditErrors(prev => ({...prev, [key]: 'Expansion cannot be empty'}));
      return;
    }
    setEditErrors(prev => {
      const next = {...prev};
      delete next[key];
      return next;
    });
    NativeModules.SettingsModule?.setString(`snippet_${key}`, val.trim());
    setSnippets(prev => ({...prev, [key]: val.trim()}));
  }, []);

  const handleDelete = useCallback((key: string) => {
    NativeModules.SettingsModule?.deleteSnippet(key);
    setSnippets(prev => {
      const next = {...prev};
      delete next[key];
      return next;
    });
  }, []);

  return (
    <View style={styles.snippetsContainer}>
      <Text style={styles.snippetsSectionTitle}>Snippets</Text>
      <Text style={styles.snippetsHint}>
        Type ;shortcode in any text field to expand. Tap the suggestion bar to commit.
      </Text>

      {Object.entries(snippets).map(([key, val]) => (
        <View key={key} style={styles.snippetRow}>
          <Text style={styles.snippetShortcode}>;{key}</Text>
          <TextInput
            style={[styles.snippetInput, styles.snippetInputFlex, editErrors[key] ? styles.inputError : null]}
            value={val}
            placeholder="expansion"
            placeholderTextColor="#555"
            autoCapitalize="none"
            autoCorrect={false}
            onChangeText={text => setSnippets(prev => ({...prev, [key]: text}))}
            onBlur={() => handleUpdate(key, val)}
            onSubmitEditing={() => handleUpdate(key, val)}
          />
          <TouchableOpacity style={styles.deleteButton} onPress={() => handleDelete(key)}>
            <Text style={styles.deleteButtonText}>x</Text>
          </TouchableOpacity>
          {editErrors[key] ? <Text style={styles.errorText}>{editErrors[key]}</Text> : null}
        </View>
      ))}

      <View style={styles.addRow}>
        <TextInput
          style={[styles.snippetInput, styles.addKeyInput]}
          value={newKey}
          placeholder=";shortcode"
          placeholderTextColor="#555"
          autoCapitalize="none"
          autoCorrect={false}
          onChangeText={t => { setNewKey(t); setAddError(''); }}
        />
        <TextInput
          style={[styles.snippetInput, styles.snippetInputFlex]}
          value={newVal}
          placeholder="expansion"
          placeholderTextColor="#555"
          autoCapitalize="none"
          autoCorrect={false}
          onChangeText={t => { setNewVal(t); setAddError(''); }}
          onSubmitEditing={handleAdd}
        />
        <TouchableOpacity
          style={[styles.addButton, (!newKey.trim() || !newVal.trim()) && styles.addButtonDisabled]}
          onPress={handleAdd}
          disabled={!newKey.trim() || !newVal.trim()}>
          <Text style={styles.addButtonText}>Add</Text>
        </TouchableOpacity>
      </View>
      {addError ? <Text style={styles.errorText}>{addError}</Text> : null}
    </View>
  );
}

// ── Layout preview renderer ───────────────────────────────────────────────────

const COLEMAK: Record<string, string> = {
  e:'f', r:'p', t:'g', y:'j', u:'l', i:'u', o:'y', p:';',
  s:'r', d:'s', f:'t', g:'d', j:'n', k:'e', l:'i', n:'k',
};
// Colemak Mod-DH (ANSI): moves D and H off the home row onto the bottom row.
// Must match android/.../KeyMaps.kt ColemakDHKeyMap exactly.
const COLEMAK_DH: Record<string, string> = {
  e:'f', r:'p', t:'b', y:'j', u:'l', i:'u', o:'y', p:';',
  s:'r', d:'s', f:'t', h:'m', j:'n', k:'e', l:'i',
  v:'d', b:'v', n:'k', m:'h',
};
const DVORAK: Record<string, string> = {
  q:"'", w:',', e:'.', r:'p', t:'y', y:'f', u:'g', i:'c', o:'r', p:'l',
  s:'o', d:'e', f:'u', g:'i', h:'d', j:'h', k:'t', l:'n',
  z:';', x:'q', c:'j', v:'k', b:'x', n:'b', m:'w',
};

function applyKeymap(label: string, keymap: string): string {
  if (label.length !== 1 || !/[a-z]/.test(label)) return label;
  if (keymap === 'colemak' || keymap === 'programmer-colemak') return COLEMAK[label] ?? label;
  if (keymap === 'colemak-dh') return COLEMAK_DH[label] ?? label;
  if (keymap === 'dvorak' || keymap === 'programmer-dvorak') return DVORAK[label] ?? label;
  return label;
}

// Column-major key data: each entry is one column top-to-bottom
// [colIndex]: array of key labels from row 0 downward
const SOFLE_COLS_L = [
  ['q','a','z','Sft'],
  ['w','s','x','Spc'],
  ['e','d','c','LWR'],
  ['r','f','v','Ctl'],
  ['t','g','b','Alt'],
];
const SOFLE_COLS_R = [
  ['y','h','n','RSE'],
  ['u','j','m','Ent'],
  ['i','k',',','Spc'],
  ['o','l','.','FN'],
  ['p',';','>>','ADJ'],
];
const FERRIS_COLS_L = [
  ['q','a','z'],
  ['w','s','x'],
  ['e','d','c'],
  ['r','f','v'],
  ['t','g','b'],
];
const FERRIS_COLS_R = [
  ['y','h','n'],
  ['u','j','m'],
  ['i','k',','],
  ['o','l','.'],
  ['p',';','>>'],
];

const SOFLE_STAG_L  = [0,    0.25, 0.50, 0.75, 1.00];
const SOFLE_STAG_R  = [1.00, 0.75, 0.50, 0.25, 0];
const FERRIS_STAG_L = [0,    0.25, 0.50, 0.50, 0.75];
const FERRIS_STAG_R = [0.75, 0.50, 0.50, 0.25, 0];

const THUMB_ROWS = ['Sft','Spc','LWR','Ctl','Alt'];
const MOD_LABELS = new Set(['Sft','Spc','LWR','RSE','Ctl','Alt','FN','ADJ','Ent','Tab','Esc','>>']);

function PreviewHalf({
  cols, stag, thumbKeys, KH, KG,
}: {
  cols: string[][];
  stag: number[];
  thumbKeys?: string[];
  KH: number;
  KG: number;
}) {
  return (
    <View style={{flex: 1}}>
      {/* Column-staggered main keys */}
      <View style={{flexDirection: 'row', gap: KG}}>
        {cols.map((col, ci) => (
          <View key={ci} style={{flex: 1, paddingTop: stag[ci] * KH}}>
            {col.map((label, ri) => {
              const isMod = MOD_LABELS.has(label);
              return (
                <View
                  key={ri}
                  style={[
                    pvStyles.key,
                    {height: KH, marginTop: ri === 0 ? 0 : KG},
                    isMod && pvStyles.keyMod,
                  ]}>
                  <Text style={pvStyles.label} numberOfLines={1}>{label}</Text>
                </View>
              );
            })}
          </View>
        ))}
      </View>
      {/* Thumb cluster — separate row, 2 wide centered keys */}
      {thumbKeys && (
        <View style={{flexDirection: 'row', gap: KG, marginTop: KG + 6, paddingHorizontal: '10%'}}>
          {thumbKeys.map((k, i) => (
            <View key={i} style={[pvStyles.key, pvStyles.keyThumb, {flex: 1, height: KH + 6}]}>
              <Text style={pvStyles.label} numberOfLines={1}>{k}</Text>
            </View>
          ))}
        </View>
      )}
    </View>
  );
}

function KeyboardPreview({layoutId, keymap}: {layoutId: string; keymap: string}) {
  const isFerris = layoutId === 'ferris';
  const KH = 30;
  const KG = 3;

  const colsL  = isFerris ? FERRIS_COLS_L : SOFLE_COLS_L;
  const colsR  = isFerris ? FERRIS_COLS_R : SOFLE_COLS_R;
  const stagL  = isFerris ? FERRIS_STAG_L : SOFLE_STAG_L;
  const stagR  = isFerris ? FERRIS_STAG_R : SOFLE_STAG_R;

  const mapCols = (cols: string[][]) =>
    cols.map(col => col.map(k => applyKeymap(k, keymap)));

  const mappedL = mapCols(colsL);
  const mappedR = mapCols(colsR);

  const thumbL = isFerris ? ['LWR','Spc'] : undefined;
  const thumbR = isFerris ? ['Spc','RSE'] : undefined;

  const topKeys = ['Tab','Esc','`','^','Ctl','Alt',':)','Bksp'];

  return (
    <View style={pvStyles.wrap}>
      {/* Top row — 8 equal keys, no stagger */}
      <View style={{flexDirection: 'row', gap: KG, marginBottom: KG}}>
        {topKeys.map((k, i) => (
          <View key={i} style={[pvStyles.key, pvStyles.keyMod, {flex: 1, height: KH}]}>
            <Text style={pvStyles.label} numberOfLines={1}>{k}</Text>
          </View>
        ))}
      </View>

      {/* Split halves */}
      <View style={{flexDirection: 'row', gap: 14}}>
        <PreviewHalf
          cols={mappedL}
          stag={stagL}
          thumbKeys={thumbL}
          KH={KH}
          KG={KG}
        />
        <PreviewHalf
          cols={mappedR}
          stag={stagR}
          thumbKeys={thumbR}
          KH={KH}
          KG={KG}
        />
      </View>
    </View>
  );
}

const pvStyles = StyleSheet.create({
  wrap: {
    backgroundColor: '#0e0e0e',
    borderRadius: 10,
    padding: 10,
    width: '100%',
    borderWidth: 1,
    borderColor: '#2a2a2a',
  },
  key: {
    flex: 1,
    backgroundColor: '#2c2c2c',
    borderRadius: 4,
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 1},
    shadowOpacity: 0.4,
    shadowRadius: 1,
    elevation: 2,
  },
  keyMod: {
    backgroundColor: '#222',
  },
  keyThumb: {
    backgroundColor: '#1a2a3a',
  },
  label: {
    color: '#bbb',
    fontSize: 8,
    fontWeight: '500',
  },
});

// ── Keymap dropdown ───────────────────────────────────────────────────────────

function KeymapDropdown({active, onSelect}: {active: string; onSelect: (id: string) => void}) {
  const [open, setOpen] = useState(false);
  const current = KEYMAPS.find(k => k.id === active) ?? KEYMAPS[0];

  return (
    <View style={styles.dropdownWrap}>
      <TouchableOpacity
        style={styles.dropdownTrigger}
        onPress={() => setOpen(o => !o)}
        activeOpacity={0.8}>
        <View style={{flex: 1}}>
          <Text style={styles.dropdownValue}>{current.name}</Text>
          <Text style={styles.dropdownDesc}>{current.desc}</Text>
        </View>
        <Text style={styles.dropdownArrow}>{open ? '▲' : '▼'}</Text>
      </TouchableOpacity>
      {open && (
        <View style={styles.dropdownList}>
          {KEYMAPS.map(k => (
            <TouchableOpacity
              key={k.id}
              style={[styles.dropdownItem, k.id === active && styles.dropdownItemActive]}
              onPress={() => { onSelect(k.id); setOpen(false); }}
              activeOpacity={0.8}>
              <Text style={[styles.dropdownItemName, k.id === active && styles.dropdownItemNameActive]}>
                {k.name}
              </Text>
              <Text style={styles.dropdownItemDesc}>{k.desc}</Text>
            </TouchableOpacity>
          ))}
        </View>
      )}
    </View>
  );
}

// ── Layouts screen ────────────────────────────────────────────────────────────

function LayoutsScreen() {
  const [activeLayout, setActiveLayout] = useState('sofle');
  const [activeKeymap, setActiveKeymap] = useState('qwerty');

  useEffect(() => {
    NativeModules.SettingsModule?.getString('layout', (val: string) => {
      if (val) setActiveLayout(val);
    });
    NativeModules.SettingsModule?.getString('keymap', (val: string) => {
      if (val) setActiveKeymap(val);
    });
  }, []);

  const selectLayout = useCallback((id: string) => {
    setActiveLayout(id);
    NativeModules.SettingsModule?.setString('layout', id);
  }, []);

  const selectKeymap = useCallback((id: string) => {
    setActiveKeymap(id);
    NativeModules.SettingsModule?.setString('keymap', id);
  }, []);

  return (
    <ScrollView style={styles.settingsScroll} contentContainerStyle={styles.settingsContainer}>
      <Text style={styles.settingsTitle}>Layout</Text>

      {/* Live preview */}
      <KeyboardPreview layoutId={activeLayout} keymap={activeKeymap} />

      {/* Test input */}
      <TextInput
        style={styles.testInput}
        placeholder="Tap here to open the keyboard and test"
        placeholderTextColor="#444"
        autoCorrect={false}
        autoCapitalize="none"
      />

      <Text style={styles.snippetsHint}>Takes effect next time you open the keyboard.</Text>

      {/* Layout picker */}
      <Text style={[styles.pickerLabel, {marginTop: 20}]}>Physical Layout</Text>
      <View style={styles.layoutRow}>
        {LAYOUTS.map(l => (
          <TouchableOpacity
            key={l.id}
            style={[styles.layoutCard, activeLayout === l.id && styles.layoutCardActive]}
            onPress={() => selectLayout(l.id)}
            activeOpacity={0.8}>
            <Text style={[styles.layoutCardName, activeLayout === l.id && styles.layoutCardNameActive]}>
              {l.name}
            </Text>
            <Text style={styles.layoutCardDesc}>{l.desc}</Text>
          </TouchableOpacity>
        ))}
      </View>

      {/* Keymap dropdown */}
      <Text style={[styles.pickerLabel, {marginTop: 20}]}>Key Map</Text>
      <KeymapDropdown active={activeKeymap} onSelect={selectKeymap} />
    </ScrollView>
  );
}

function SettingsScreen() {
  const handleEnable = useCallback(() => {
    NativeModules.IMEHelper?.openSettings();
  }, []);

  const handleSwitch = useCallback(() => {
    NativeModules.IMEHelper?.showPicker();
  }, []);

  return (
    <ScrollView style={styles.settingsScroll} contentContainerStyle={styles.settingsContainer}>
      <Text style={styles.settingsTitle}>Settings</Text>
      <TouchableOpacity style={styles.settingsButton} onPress={handleEnable}>
        <Text style={styles.settingsButtonText}>Manage Keyboards</Text>
      </TouchableOpacity>
      <Text style={styles.settingsHint}>
        Opens system settings to enable/disable keyboards.
      </Text>
      <TouchableOpacity
        style={[styles.settingsButton, styles.switchButton]}
        onPress={handleSwitch}>
        <Text style={styles.settingsButtonText}>Switch Keyboard</Text>
      </TouchableOpacity>
      <Text style={styles.settingsHint}>
        Opens IME picker to switch active keyboard.
      </Text>
      <View style={styles.divider} />
      <SnippetsEditor />
    </ScrollView>
  );
}

const LAYOUTS: {id: string; name: string; desc: string}[] = [
  {id: 'sofle',  name: 'Sofle V5',      desc: '5×4 cols + 5 thumb keys'},
  {id: 'ferris', name: 'Ferris Sweep',  desc: '5×3 cols + 2 thumb keys'},
];

const KEYMAPS: {id: string; name: string; desc: string}[] = [
  {id: 'qwerty',             name: 'QWERTY',          desc: 'Standard layout'},
  {id: 'colemak',            name: 'Colemak',          desc: 'Optimised for English, 17 keys change'},
  {id: 'colemak-dh',         name: 'Colemak-DH',       desc: 'Colemak with D and H on bottom row'},
  {id: 'dvorak',             name: 'Dvorak',            desc: 'Vowels left, consonants right'},
  {id: 'programmer-dvorak',  name: 'Prog. Dvorak',      desc: 'Dvorak with symbols unshifted'},
  {id: 'programmer-colemak', name: 'Prog. Colemak',     desc: 'Colemak with programmer symbol row'},
];

const THEMES: {id: string; name: string; desc: string; bg: string; key: string; accent: string; text: string; textMuted: string}[] = [
  {id: 'carbon',   name: 'Carbon',   desc: 'Neutral dark grey',        bg: '#111111', key: '#2c2c2c', accent: '#4a9eff', text: '#e0e0e0', textMuted: '#888888'},
  {id: 'midnight', name: 'Midnight', desc: 'Deep navy blue',           bg: '#080d14', key: '#0f1c2e', accent: '#3b82f6', text: '#e0e0e0', textMuted: '#888888'},
  {id: 'obsidian', name: 'Obsidian', desc: 'Near-black with violet',   bg: '#0c0c10', key: '#1a1a24', accent: '#8b5cf6', text: '#e0e0e0', textMuted: '#888888'},
  {id: 'ash',      name: 'Ash',      desc: 'Warm grey, amber accent',  bg: '#141210', key: '#272320', accent: '#f59e0b', text: '#e0e0e0', textMuted: '#888888'},
  {id: 'moss',     name: 'Moss',     desc: 'Dark green-grey, teal',    bg: '#0d1210', key: '#1a2420', accent: '#2dd4bf', text: '#e0e0e0', textMuted: '#888888'},
  {id: 'dusk',     name: 'Dusk',     desc: 'Slate purple, rose',       bg: '#10101a', key: '#1e1e2e', accent: '#f43f5e', text: '#e0e0e0', textMuted: '#888888'},
  {id: 'iron',     name: 'Iron',     desc: 'Cool steel, cyan',         bg: '#0e1014', key: '#1c2028', accent: '#06b6d4', text: '#e0e0e0', textMuted: '#888888'},
  {id: 'ember',    name: 'Ember',    desc: 'Warm brown, orange',       bg: '#100c08', key: '#241c14', accent: '#ea580c', text: '#e0e0e0', textMuted: '#888888'},
  {id: 'frost',    name: 'Frost',    desc: 'Bluish white, cool blue',  bg: '#eef3fa', key: '#dbe6f5', accent: '#2f6fed', text: '#16233d', textMuted: '#5b7096'},
];

function ThemesScreen() {
  const [active, setActive] = useState('carbon');

  useEffect(() => {
    NativeModules.SettingsModule?.getString('theme', (val: string) => {
      if (val) setActive(val);
    });
  }, []);

  const select = useCallback((id: string) => {
    setActive(id);
    NativeModules.SettingsModule?.setString('theme', id);
  }, []);

  return (
    <ScrollView style={styles.settingsScroll} contentContainerStyle={styles.settingsContainer}>
      <Text style={styles.settingsTitle}>Theme</Text>
      <Text style={styles.snippetsHint}>Takes effect next time you open the keyboard.</Text>
      <View style={styles.themeGrid}>
        {THEMES.map(t => (
          <TouchableOpacity
            key={t.id}
            style={[styles.themeCard, {backgroundColor: t.bg}, active === t.id && styles.themeCardActive]}
            onPress={() => select(t.id)}
            activeOpacity={0.8}>
            <View style={styles.themePreview}>
              {[0,1,2,3,4].map(i => (
                <View key={i} style={[styles.themeKey, {backgroundColor: t.key}]} />
              ))}
            </View>
            <View style={[styles.themeAccentBar, {backgroundColor: t.accent}]} />
            <View style={styles.themeInfo}>
              <Text style={[styles.themeName, {color: t.text}]}>{t.name}</Text>
              <Text style={[styles.themeDesc, {color: t.textMuted}]}>{t.desc}</Text>
            </View>
            {active === t.id && (
              <View style={[styles.themeCheck, {borderColor: t.accent}]}>
                <Text style={[styles.themeCheckText, {color: t.accent}]}>✓</Text>
              </View>
            )}
          </TouchableOpacity>
        ))}
      </View>
    </ScrollView>
  );
}

function PlaceholderScreen({tab}: {tab: Tab}) {
  return (
    <View style={styles.placeholder}>
      <Text style={styles.placeholderText}>
        {tab.charAt(0).toUpperCase() + tab.slice(1)} — coming soon
      </Text>
    </View>
  );
}

function App() {
  const isDarkMode = useColorScheme() === 'dark';
  const [activeTab, setActiveTab] = useState<Tab>('settings');

  return (
    <SafeAreaProvider>
      <SafeAreaView edges={['top', 'bottom']} style={styles.safe}>
        <StatusBar
          barStyle={isDarkMode ? 'light-content' : 'dark-content'}
        />
        <View style={styles.tabBar}>
          {(['settings', 'themes', 'layouts', 'languages'] as Tab[]).map(
            tab => (
              <TouchableOpacity
                key={tab}
                style={[
                  styles.tab,
                  activeTab === tab && styles.activeTab,
                ]}
                onPress={() => setActiveTab(tab)}>
                <Text
                  style={[
                    styles.tabText,
                    activeTab === tab && styles.activeTabText,
                  ]}>
                  {tab.charAt(0).toUpperCase() + tab.slice(1)}
                </Text>
              </TouchableOpacity>
            ),
          )}
        </View>
        {activeTab === 'settings' ? (
          <SettingsScreen />
        ) : activeTab === 'themes' ? (
          <ThemesScreen />
        ) : activeTab === 'layouts' ? (
          <LayoutsScreen />
        ) : (
          <PlaceholderScreen tab={activeTab} />
        )}
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  safe: {
    flex: 1,
    backgroundColor: '#111',
  },
  tabBar: {
    flexDirection: 'row',
    backgroundColor: '#1a1a1a',
    borderBottomWidth: 1,
    borderBottomColor: '#333',
  },
  tab: {
    flex: 1,
    paddingVertical: 10,
    alignItems: 'center',
  },
  activeTab: {
    borderBottomWidth: 2,
    borderBottomColor: '#4a9eff',
  },
  tabText: {
    color: '#777',
    fontSize: 13,
    fontWeight: '500',
  },
  activeTabText: {
    color: '#4a9eff',
  },
  settingsScroll: {
    flex: 1,
  },
  settingsContainer: {
    alignItems: 'center',
    padding: 24,
    paddingBottom: 48,
  },
  settingsTitle: {
    color: '#e0e0e0',
    fontSize: 22,
    fontWeight: '600',
    marginBottom: 24,
  },
  settingsButton: {
    backgroundColor: '#2d6b3f',
    paddingVertical: 14,
    paddingHorizontal: 32,
    borderRadius: 8,
  },
  switchButton: {
    backgroundColor: '#1a3a5c',
    marginTop: 20,
  },
  settingsButtonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
  settingsHint: {
    color: '#777',
    fontSize: 13,
    textAlign: 'center',
    marginTop: 16,
    lineHeight: 20,
    maxWidth: 300,
  },
  placeholder: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  placeholderText: {
    color: '#555',
    fontSize: 16,
  },
  divider: {
    width: '100%',
    height: 1,
    backgroundColor: '#2a2a2a',
    marginVertical: 24,
  },
  snippetsContainer: {
    width: '100%',
  },
  snippetsSectionTitle: {
    color: '#e0e0e0',
    fontSize: 18,
    fontWeight: '600',
    marginBottom: 6,
  },
  snippetsHint: {
    color: '#666',
    fontSize: 12,
    lineHeight: 18,
    marginBottom: 20,
  },
  snippetRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 10,
    flexWrap: 'wrap',
  },
  snippetShortcode: {
    color: '#4a9eff',
    fontFamily: 'monospace',
    fontSize: 13,
    width: 56,
  },
  snippetInput: {
    backgroundColor: '#1e1e1e',
    borderWidth: 1,
    borderColor: '#333',
    borderRadius: 6,
    color: '#e0e0e0',
    paddingHorizontal: 10,
    paddingVertical: 8,
    fontSize: 13,
    fontFamily: 'monospace',
  },
  snippetInputFlex: {
    flex: 1,
  },
  inputError: {
    borderColor: '#c0392b',
  },
  deleteButton: {
    marginLeft: 8,
    paddingHorizontal: 10,
    paddingVertical: 8,
    backgroundColor: '#2a1a1a',
    borderRadius: 6,
  },
  deleteButtonText: {
    color: '#c0392b',
    fontSize: 13,
    fontWeight: '700',
  },
  addRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 12,
    gap: 6,
  },
  addKeyInput: {
    width: 88,
  },
  addButton: {
    backgroundColor: '#2d6b3f',
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 6,
  },
  addButtonDisabled: {
    backgroundColor: '#1a3a28',
  },
  addButtonText: {
    color: '#fff',
    fontSize: 13,
    fontWeight: '600',
  },
  errorText: {
    color: '#c0392b',
    fontSize: 12,
    width: '100%',
    marginTop: 4,
  },
  testInput: {
    width: '100%',
    backgroundColor: '#1a1a1a',
    borderWidth: 1,
    borderColor: '#333',
    borderRadius: 8,
    color: '#e0e0e0',
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 14,
    marginTop: 12,
  },
  pickerSection: {
    width: '100%',
  },
  pickerLabel: {
    color: '#e0e0e0',
    fontSize: 14,
    fontWeight: '600',
    marginBottom: 10,
  },
  layoutRow: {
    flexDirection: 'row',
    gap: 10,
  },
  layoutCard: {
    flex: 1,
    backgroundColor: '#1e1e1e',
    borderRadius: 8,
    padding: 12,
    borderWidth: 2,
    borderColor: 'transparent',
  },
  layoutCardActive: {
    borderColor: '#4a9eff',
    backgroundColor: '#0d1f33',
  },
  layoutCardName: {
    color: '#888',
    fontSize: 13,
    fontWeight: '600',
    marginBottom: 2,
  },
  layoutCardNameActive: {
    color: '#4a9eff',
  },
  layoutCardDesc: {
    color: '#555',
    fontSize: 11,
  },
  keymapRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  keymapChip: {
    paddingHorizontal: 12,
    paddingVertical: 7,
    borderRadius: 20,
    backgroundColor: '#1e1e1e',
    borderWidth: 1.5,
    borderColor: '#333',
  },
  keymapChipActive: {
    borderColor: '#4a9eff',
    backgroundColor: '#0d1f33',
  },
  keymapChipText: {
    color: '#777',
    fontSize: 12,
    fontWeight: '500',
  },
  keymapChipTextActive: {
    color: '#4a9eff',
  },
  dropdownWrap: {
    width: '100%',
  },
  dropdownTrigger: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#1e1e1e',
    borderWidth: 1,
    borderColor: '#333',
    borderRadius: 8,
    paddingHorizontal: 14,
    paddingVertical: 12,
  },
  dropdownValue: {
    color: '#e0e0e0',
    fontSize: 14,
    fontWeight: '600',
  },
  dropdownDesc: {
    color: '#555',
    fontSize: 11,
    marginTop: 1,
  },
  dropdownArrow: {
    color: '#555',
    fontSize: 11,
    marginLeft: 8,
  },
  dropdownList: {
    backgroundColor: '#1a1a1a',
    borderWidth: 1,
    borderColor: '#333',
    borderTopWidth: 0,
    borderBottomLeftRadius: 8,
    borderBottomRightRadius: 8,
    overflow: 'hidden',
  },
  dropdownItem: {
    paddingHorizontal: 14,
    paddingVertical: 11,
    borderTopWidth: 1,
    borderTopColor: '#252525',
  },
  dropdownItemActive: {
    backgroundColor: '#0d1f33',
  },
  dropdownItemName: {
    color: '#888',
    fontSize: 13,
    fontWeight: '500',
  },
  dropdownItemNameActive: {
    color: '#4a9eff',
  },
  dropdownItemDesc: {
    color: '#444',
    fontSize: 11,
    marginTop: 1,
  },
  themeGrid: {
    width: '100%',
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
    justifyContent: 'space-between',
  },
  themeCard: {
    width: '47%',
    borderRadius: 10,
    overflow: 'hidden',
    borderWidth: 2,
    borderColor: 'transparent',
  },
  themeCardActive: {
    borderColor: '#4a9eff',
  },
  themePreview: {
    flexDirection: 'row',
    gap: 4,
    padding: 10,
    paddingBottom: 6,
  },
  themeKey: {
    flex: 1,
    height: 22,
    borderRadius: 4,
  },
  themeAccentBar: {
    height: 2,
    marginHorizontal: 10,
    borderRadius: 1,
    marginBottom: 8,
  },
  themeInfo: {
    paddingHorizontal: 10,
    paddingBottom: 10,
  },
  themeName: {
    fontSize: 13,
    fontWeight: '600',
  },
  themeDesc: {
    fontSize: 11,
    marginTop: 1,
  },
  themeCheck: {
    position: 'absolute',
    top: 8,
    right: 8,
    width: 18,
    height: 18,
    borderRadius: 9,
    borderWidth: 1.5,
    alignItems: 'center',
    justifyContent: 'center',
  },
  themeCheckText: {
    fontSize: 10,
    fontWeight: '700',
  },
});

export default App;
