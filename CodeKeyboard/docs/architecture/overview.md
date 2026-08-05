# Architecture Overview

## Layers

```
┌─────────────────────────────────────────────┐
│            React Native (transitional)       │
│  App.tsx, Settings, Themes, Preview field    │
│  Will be removed when Kotlin layer is full   │
└────────────────────┬────────────────────────┘
                     │ RN bridge (CodeKeyboardModule)
┌────────────────────▼────────────────────────┐
│              Android IME Layer (Kotlin)      │
│                                             │
│  CodeKeyboardIME                            │
│    ├── ComposingEngine      (ADR-002)        │
│    ├── SuggestionBarView    (ADR-003)        │
│    ├── SnippetStore         (ADR-003)        │
│    ├── Trie                 (ADR-003)        │
│    └── AndroidTextInputConnection (ADR-001)  │
│                                             │
│  NativeKeyboardView (key rendering)         │
│  SofleLayoutComputer (key geometry)         │
│  KeyboardState (modifier/layer state)       │
│  TapMachine (double-tap detection)          │
└────────────────────┬────────────────────────┘
                     │ InputConnection API
┌────────────────────▼────────────────────────┐
│           Target App Text Field              │
│  WhatsApp, Chrome, Termux, vim, etc.        │
└─────────────────────────────────────────────┘
```

## Key interfaces

- `TextInputConnection` — platform abstraction over InputConnection/UITextDocumentProxy (ADR-001)
- `ComposingEngine` — composing buffer, field-type detection, flush/clear logic (ADR-002)
- `TextInputConnection` implementations: Android, iOS, Fake (test) (ADR-001)

## ADR index

| ADR | Decision |
|---|---|
| ADR-001 | TextInputConnection platform abstraction interface + implementations |
| ADR-002 | ComposingEngine: composing buffer, supportsComposing flag, field detection |
| ADR-003 | Kotlin-native suggestions, trie, snippet system — no RN bridge |

## Plans index

| Plan | Status |
|---|---|
| plan-suggestions-snippets.md | Phase breakdown for suggestions + snippets feature |
| plan-phase1-composing.md | Detailed breakdown: setComposingText + ComposingBuffer |
| plan-phase2-trie.md | Detailed breakdown: TRIE2 format spec, Trie.kt, test cases |
| plan-phase3-suggestion-bar.md | Detailed breakdown: SuggestionBarView, slot logic, RN suppression |

## What is transitional (will be removed)

- `src/keyboard/SuggestionBar.tsx` — replaced by `SuggestionBarView.kt`
- `src/keyboard/Dictionary.ts`, `Trie.ts`, `DictionaryTrieData.ts` — replaced by `Trie.kt`
- RN bridge (`CodeKeyboardModule.kt`, `CodeKeyboardModuleHolder.kt`) — when RN removed
- All of `src/` and `App.tsx` — when settings/themes ported to native Android
