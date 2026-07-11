import React, {useState, useCallback, useEffect} from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  StatusBar,
  useColorScheme,
  NativeModules,
  Switch,
} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import {Keyboard} from './src/keyboard/Keyboard';

type Tab = 'keyboard' | 'settings' | 'themes' | 'languages';

function SettingsScreen() {
  const [fallthroughEnabled, setFallthroughEnabled] = useState(true);

  useEffect(() => {
    NativeModules.SettingsModule?.getFallthroughBehavior().then(
      (behavior: string) => {
        setFallthroughEnabled(behavior === 'insert_text');
      },
    );
  }, []);

  const handleEnable = useCallback(() => {
    NativeModules.IMEHelper?.openSettings();
  }, []);

  const handleSwitch = useCallback(() => {
    NativeModules.IMEHelper?.showPicker();
  }, []);

  const handleFallthroughToggle = useCallback((value: boolean) => {
    setFallthroughEnabled(value);
    NativeModules.SettingsModule?.setFallthroughBehavior(
      value ? 'insert_text' : 'do_nothing',
    );
  }, []);

  return (
    <View style={styles.settingsContainer}>
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

      <View style={styles.settingRow}>
        <View style={styles.settingLabelContainer}>
          <Text style={styles.settingLabel}>Insert text for unknown keys</Text>
          <Text style={styles.settingDescription}>
            When disabled, keys without actions do nothing instead of inserting
            their label as text.
          </Text>
        </View>
        <Switch
          value={fallthroughEnabled}
          onValueChange={handleFallthroughToggle}
          trackColor={{false: '#333', true: '#4a9eff'}}
          thumbColor={fallthroughEnabled ? '#fff' : '#666'}
        />
      </View>
    </View>
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
  settingsContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
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
  settingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: '#1a1a1a',
    padding: 16,
    borderRadius: 8,
    marginTop: 32,
    width: '100%',
    maxWidth: 400,
  },
  settingLabelContainer: {
    flex: 1,
    marginRight: 16,
  },
  settingLabel: {
    color: '#e0e0e0',
    fontSize: 15,
    fontWeight: '500',
    marginBottom: 4,
  },
  settingDescription: {
    color: '#777',
    fontSize: 12,
    lineHeight: 16,
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
});

export default App;
