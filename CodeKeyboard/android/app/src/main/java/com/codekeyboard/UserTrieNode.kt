package com.codekeyboard

class UserTrieNode {
    val children = HashMap<Char, UserTrieNode>()
    var frequency: Int = 0
    var maxDescendantFreq: Int = 0
    var lastDecayEpoch: Int = 0

    val isTerminal: Boolean get() = frequency > 0
}
