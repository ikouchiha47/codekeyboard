import React from 'react';
import {View, Text, TouchableOpacity, StyleSheet} from 'react-native';

interface Props {
  suggestions: string[];
  onSelect: (word: string) => void;
}

export function SuggestionBar({suggestions, onSelect}: Props) {
  return (
    <View style={styles.bar}>
      {suggestions.map((w, i) => (
        <TouchableOpacity
          key={w}
          style={[styles.pill, i === 0 && styles.best]}
          onPress={() => onSelect(w)}>
          <Text style={[styles.pillText, i === 0 && styles.bestText]}>
            {w}
          </Text>
        </TouchableOpacity>
      ))}
      {suggestions.length === 0 && (
        <View style={styles.placeholder} />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    gap: 6,
    paddingHorizontal: 8,
    paddingVertical: 4,
    minHeight: 34,
    backgroundColor: '#1e1e1e',
    borderBottomWidth: 1,
    borderBottomColor: '#333',
    alignItems: 'center',
  },
  pill: {
    flex: 1,
    paddingVertical: 5,
    paddingHorizontal: 8,
    borderRadius: 4,
    alignItems: 'center',
    justifyContent: 'center',
  },
  best: {
    backgroundColor: '#1a3a5c',
  },
  pillText: {
    color: '#999',
    fontSize: 14,
    fontFamily: 'monospace',
  },
  bestText: {
    color: '#4a9eff',
    fontWeight: '600',
  },
  placeholder: {
    flex: 1,
  },
});
