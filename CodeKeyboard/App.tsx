import React, {useState, useCallback} from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  StatusBar,
  useColorScheme,
  NativeModules,
} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import {Keyboard} from './src/keyboard/Keyboard';

type Tab = 'keyboard' | 'settings' | 'themes' | 'languages';

function SettingsScreen() {
  const handleEnable = useCallback(() => {
    NativeModules.IMEHelper?.showPicker();
  }, []);

  return (
    <View style={styles.settingsContainer}>
      <Text style={styles.settingsTitle}>Settings</Text>
      <TouchableOpacity style={styles.settingsButton} onPress={handleEnable}>
        <Text style={styles.settingsButtonText}>Enable CodeKeyboard</Text>
      </TouchableOpacity>
      <Text style={styles.settingsHint}>
        Opens the IME picker. Select "CodeKeyboard" from the list to enable
        it, then switch to it in any text field.
      </Text>
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

function App() {
  const isDarkMode = useColorScheme() === 'dark';
  const [activeTab, setActiveTab] = useState<Tab>('keyboard');

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
});

export default App;
