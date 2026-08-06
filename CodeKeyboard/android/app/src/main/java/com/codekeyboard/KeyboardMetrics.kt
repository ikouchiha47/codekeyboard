package com.codekeyboard

interface KeyboardMetrics {
    fun incr(metric: String, vararg tags: Pair<String, String>)
    fun histogram(metric: String, value: Double, vararg tags: Pair<String, String>)
}

object NoOpMetrics : KeyboardMetrics {
    override fun incr(metric: String, vararg tags: Pair<String, String>) = Unit
    override fun histogram(metric: String, value: Double, vararg tags: Pair<String, String>) = Unit
}

object LogMetrics : KeyboardMetrics {
    private const val TAG = "CKB_METRICS"

    override fun incr(metric: String, vararg tags: Pair<String, String>) {
        android.util.Log.d(TAG, format(metric, 1.0, "c", tags))
    }

    override fun histogram(metric: String, value: Double, vararg tags: Pair<String, String>) {
        android.util.Log.d(TAG, format(metric, value, "h", tags))
    }

    private fun format(metric: String, value: Double, type: String, tags: Array<out Pair<String, String>>): String {
        val tagStr = if (tags.isEmpty()) "" else "|#" + tags.joinToString(",") { "${it.first}:${it.second}" }
        return "$metric:$value|$type$tagStr"
    }
}

object Metrics {
    var client: KeyboardMetrics = NoOpMetrics

    fun incr(metric: String, vararg tags: Pair<String, String>) = client.incr(metric, *tags)
    fun histogram(metric: String, value: Double, vararg tags: Pair<String, String>) = client.histogram(metric, value, *tags)
}
