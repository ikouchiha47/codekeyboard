import React, {useCallback, useRef} from 'react';
import {
  Text,
  StyleSheet,
  Pressable,
  Animated,
} from 'react-native';
import {KeySpec, isModifier} from './Layout';

interface Props {
  spec: KeySpec;
  latched: boolean;
  locked: boolean;
  onPress: (spec: KeySpec) => void;
}

function displayLabel(s: string): string {
  if (s === ' ') return '\u2423';
  return s;
}

export const Key = React.memo(function Key({
  spec,
  latched,
  locked,
  onPress,
}: Props) {
  const scale = useRef(new Animated.Value(1)).current;
  const isMod = isModifier(spec.action ?? '');

  const handlePressIn = useCallback(() => {
    Animated.spring(scale, {
      toValue: 0.94,
      useNativeDriver: true,
    }).start();
  }, [scale]);

  const handlePressOut = useCallback(() => {
    Animated.spring(scale, {
      toValue: 1,
      useNativeDriver: true,
    }).start();
  }, [scale]);

  return (
    <Pressable
      onPress={() => onPress(spec)}
      onPressIn={handlePressIn}
      onPressOut={handlePressOut}
      style={[
        styles.key,
        {flex: spec.width},
        locked && styles.locked,
      ]}>
      <Animated.View
        style={[
          styles.inner,
          {
            transform: [{scale}],
            backgroundColor: locked
              ? '#264f78'
              : latched
              ? '#1e3a5f'
              : isMod
              ? '#252525'
              : '#2c2c2c',
          },
          latched && !locked && styles.latched,
        ]}>
        <Text style={styles.label}>{displayLabel(spec.label)}</Text>
        {spec.shift ? (
          <Text style={styles.sub}>{spec.shift}</Text>
        ) : null}
      </Animated.View>
    </Pressable>
  );
});

const styles = StyleSheet.create({
  key: {
    height: 46,
    margin: 2,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 1},
    shadowOpacity: 0.3,
    shadowRadius: 1,
    elevation: 2,
  },
  inner: {
    flex: 1,
    borderRadius: 6,
    justifyContent: 'center',
    alignItems: 'center',
  },
  latched: {
    borderWidth: 2,
    borderColor: '#4a9eff',
  },
  locked: {},
  label: {
    color: '#e0e0e0',
    fontSize: 12,
    fontWeight: '500',
  },
  sub: {
    position: 'absolute',
    top: 2,
    right: 4,
    fontSize: 8,
    color: '#777',
  },
});
