package com.codekeyboard

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import java.util.concurrent.TimeUnit

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          add(CodeKeyboardPackage())
        },
    )
  }

  override fun onCreate() {
    super.onCreate()
    loadReactNative(this)
    reactHost.start()
    scheduleTrieDecay()
  }

  private fun scheduleTrieDecay() {
    val constraints = Constraints.Builder()
        .setRequiresCharging(true)
        .setRequiresDeviceIdle(true)
        .build()
    val request = PeriodicWorkRequestBuilder<TrieDecayWorker>(1, TimeUnit.DAYS)
        .setConstraints(constraints)
        .build()
    WorkManager.getInstance(this).enqueueUniquePeriodicWork(
        "trie_decay",
        ExistingPeriodicWorkPolicy.KEEP,
        request,
    )
  }
}
