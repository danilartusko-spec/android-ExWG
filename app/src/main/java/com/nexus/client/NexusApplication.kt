package com.nexus.client

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nexus.client.data.ProfileStore
import com.nexus.client.routing.RoutingRepository
import com.nexus.client.routing.RoutingUpdateWorker
import com.nexus.client.tunnel.TunnelManager
import com.nexus.client.update.AppUpdateManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NexusApplication : Application() {
    var storageSummary: String = ""
        private set
    lateinit var profiles: ProfileStore
        private set
    lateinit var routing: RoutingRepository
        private set
    lateinit var tunnel: TunnelManager
        private set
    lateinit var updates: AppUpdateManager
        private set

    fun currentStorageSummary(): String =
        com.nexus.client.data.InstallationState.describe(this, profiles.list().size)

    override fun onCreate() {
        super.onCreate()
        EventLog.initialize(this)
        EventLog.add("Запуск приложения")
        profiles = ProfileStore(this)
        storageSummary = currentStorageSummary()
        EventLog.add("SESSION.start $storageSummary")
        routing = RoutingRepository(this, profiles)
        tunnel = TunnelManager(this)
        updates = AppUpdateManager(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            routing.migrateLegacyBundle()
            runCatching { profiles.prewarmCrypto() }.onFailure { EventLog.error("profiles.crypto.prewarm", it) }
            runCatching {
                routing.cached()
                routing.summary()
            }.onFailure { EventLog.error("routing.cache.prewarm", it) }
            routing.refreshStale()
            delay(2500L)
            updates.checkForUpdates(silent = true)
        }
        val request = PeriodicWorkRequestBuilder<RoutingUpdateWorker>(72, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            RoutingUpdateWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
