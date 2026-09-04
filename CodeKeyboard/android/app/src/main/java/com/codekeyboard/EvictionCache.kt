package com.codekeyboard

interface EvictionCache<K, V> {
    fun put(key: K, value: V)
    fun get(key: K): V?
    fun entries(): Map<K, V>
}

/** Simple LRU backed by LinkedHashMap. */
class LRUCache<K, V>(private val maxSize: Int) : EvictionCache<K, V> {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<K, V>) = size > maxSize
    }
    override fun put(key: K, value: V) { map[key] = value }
    override fun get(key: K): V? = map[key]
    override fun entries(): Map<K, V> = HashMap(map)
}

/**
 * 2Q (Two-Queue) cache.
 * Algorithm: Johnson & Shasha, VLDB 1994.
 * Logic ported from Caffeine's TwoQueuePolicy simulator.
 *
 * IN   — probationary FIFO (20% of max): new entries land here.
 * OUT  — ghost FIFO (50% of max): keys evicted from IN, no values.
 * MAIN — LRU: entries promoted from IN via OUT on second access.
 */
class TwoQueueCache<K, V>(private val maximumSize: Int) : EvictionCache<K, V> {

    private val maxIn  = (maximumSize * 0.20).toInt()
    private val maxOut = (maximumSize * 0.50).toInt()

    private enum class QueueType { IN, OUT, MAIN }

    private inner class Node(val key: K, var value: V? = null) {
        var type = QueueType.IN
        var prev: Node = this
        var next: Node = this

        fun remove() {
            prev.next = next; next.prev = prev
            prev = this; next = this
        }
        fun appendToTail(head: Node) {
            val last = head.prev
            last.next = this; prev = last
            next = head; head.prev = this
        }
        fun moveToTail(head: Node) { remove(); appendToTail(head) }
    }

    private val data     = HashMap<K, Node>()
    private val headIn   = Node(null as K)
    private val headOut  = Node(null as K)
    private val headMain = Node(null as K)
    private var sizeIn = 0; private var sizeOut = 0; private var sizeMain = 0

    override fun put(key: K, value: V) {
        val node = data[key]
        if (node != null) {
            when (node.type) {
                QueueType.MAIN -> { node.value = value; node.moveToTail(headMain) }
                QueueType.OUT  -> {
                    node.remove(); sizeOut--
                    node.value = value
                    reclaimFor(node)
                    node.appendToTail(headMain)
                    node.type = QueueType.MAIN; sizeMain++
                }
                QueueType.IN   -> node.value = value
            }
        } else {
            val n = Node(key, value).also { it.type = QueueType.IN }
            reclaimFor(n)
            n.appendToTail(headIn); sizeIn++
        }
    }

    override fun get(key: K): V? = data[key]?.value

    override fun entries(): Map<K, V> {
        val result = HashMap<K, V>(sizeIn + sizeMain)
        var n = headIn.next
        while (n !== headIn) { n.value?.let { result[n.key] = it }; n = n.next }
        n = headMain.next
        while (n !== headMain) { n.value?.let { result[n.key] = it }; n = n.next }
        return result
    }

    private fun reclaimFor(node: Node) {
        if (sizeMain + sizeIn < maximumSize) {
            data[node.key] = node
        } else if (sizeIn > maxIn) {
            val evicted = headIn.next
            evicted.remove(); sizeIn--
            evicted.value = null
            evicted.appendToTail(headOut)
            evicted.type = QueueType.OUT; sizeOut++
            if (sizeOut > maxOut) {
                val drop = headOut.next
                data.remove(drop.key); drop.remove(); sizeOut--
            }
            data[node.key] = node
        } else {
            val victim = headMain.next
            data.remove(victim.key); victim.remove(); sizeMain--
            data[node.key] = node
        }
    }
}
