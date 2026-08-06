package com.codekeyboard

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TrieDecayWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val file = File(applicationContext.filesDir, "user.trie")
        if (!file.exists()) return Result.success()

        return try {
            val trie = withContext(Dispatchers.IO) { UserTrie.load(file) }
            val newEpoch = trie.decayEpoch + 1
            trie.applyDecay(factor = 0.9, newEpoch = newEpoch)
            withContext(Dispatchers.IO) { trie.save(file) }
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
