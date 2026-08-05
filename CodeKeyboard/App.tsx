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
import {Keyboard} from './src/keyboard/Keyboard';

type Tab = 'keyboard' | 'settings' | 'themes' | 'languages';

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

function PlaceholderScreen({tab}: {tab: Tab}) {
  return (
    <View style={styles.placeholder}>
      <Text style={styles.placeholderText}>
        {tab.charAt(0).toUpperCase() + tab.slice(1)} — coming soon
      </Text>
    </View>
  );
}

function App({mode}: {mode?: string}) {
  const isDarkMode = useColorScheme() === 'dark';
  const [activeTab, setActiveTab] = useState<Tab>('keyboard');

  if (mode === 'ime') {
    return <Keyboard mode="ime" />;
  }

  return (
    <SafeAreaProvider>
      <SafeAreaView edges={['top', 'bottom']} style={styles.safe}>
        <StatusBar
          barStyle={isDarkMode ? 'light-content' : 'dark-content'}
        />
        <View style={styles.tabBar}>
          {(['keyboard', 'settings', 'themes', 'languages'] as Tab[]).map(
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
        {activeTab === 'keyboard' ? (
          <Keyboard />
        ) : activeTab === 'settings' ? (
          <SettingsScreen />
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
});

export default App;
