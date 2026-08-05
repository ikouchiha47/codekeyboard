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

const SNIPPET_KEYS = [
  {key: 'em',   label: 'Email',    hint: 'your@email.com'},
  {key: 'ph',   label: 'Phone',    hint: '+1 555 ...'},
  {key: 'addr', label: 'Address',  hint: '123 Main St ...'},
  {key: 'me',   label: 'Name',     hint: 'Full name'},
  {key: 'gh',   label: 'GitHub',   hint: 'https://github.com/...'},
  {key: 'li',   label: 'LinkedIn', hint: 'https://linkedin.com/in/...'},
];

function SnippetsEditor() {
  const [values, setValues] = useState<Record<string, string>>({});

  useEffect(() => {
    SNIPPET_KEYS.forEach(({key}) => {
      NativeModules.SettingsModule?.getString(`snippet_${key}`, (value: string) => {
        setValues(prev => ({...prev, [key]: value ?? ''}));
      });
    });
  }, []);

  const handleChange = (key: string, text: string) => {
    setValues(prev => ({...prev, [key]: text}));
  };

  const handleSave = (key: string) => {
    NativeModules.SettingsModule?.setString(`snippet_${key}`, values[key] ?? '');
  };

  return (
    <View style={styles.snippetsContainer}>
      <Text style={styles.snippetsSectionTitle}>Snippets</Text>
      <Text style={styles.snippetsHint}>
        Type ;shortcode in any field to expand. Tap a slot in the suggestion bar to commit.
      </Text>
      {SNIPPET_KEYS.map(({key, label, hint}) => (
        <View key={key} style={styles.snippetRow}>
          <Text style={styles.snippetLabel}>
            <Text style={styles.snippetShortcode}>;{key}</Text>{'  '}{label}
          </Text>
          <TextInput
            style={styles.snippetInput}
            value={values[key] ?? ''}
            placeholder={hint}
            placeholderTextColor="#555"
            autoCapitalize="none"
            autoCorrect={false}
            onChangeText={text => handleChange(key, text)}
            onBlur={() => handleSave(key)}
            onSubmitEditing={() => handleSave(key)}
          />
        </View>
      ))}
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
    marginBottom: 16,
  },
  snippetLabel: {
    color: '#aaa',
    fontSize: 13,
    marginBottom: 6,
  },
  snippetShortcode: {
    color: '#4a9eff',
    fontFamily: 'monospace',
  },
  snippetInput: {
    backgroundColor: '#1e1e1e',
    borderWidth: 1,
    borderColor: '#333',
    borderRadius: 6,
    color: '#e0e0e0',
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 14,
    fontFamily: 'monospace',
  },
});

export default App;
