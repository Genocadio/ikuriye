package com.gocavgo.ikuriye.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gocavgo.ikuriye.data.AuthRepository
import com.gocavgo.ikuriye.data.PackageCache
import com.gocavgo.ikuriye.data.PackageRepository
import com.gocavgo.ikuriye.data.PagedResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background worker that periodically syncs package data with the server.
 *
 * Strategy:
 * - **Driver packages**: cached locally, synced every 15 minutes
 * - **Client packages**: cached locally, synced every 15 minutes
 * - **Driver offers**: NOT synced (always fresh when user opens the tab)
 *
 * The worker:
 * 1. Fetches latest packages from server
 * 2. Merges with local cache (smart merge)
 * 3. Updates the in-memory state if app is in foreground
 *
 * This ensures data is fresh when user opens the app, even if they
 * haven't used it for a while.
 */
class PackageSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "PackageSyncWorker"
        private const val WORK_NAME = "package_sync"

        /**
         * Schedule periodic background sync.
         * Called on login/app start.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PackageSyncWorker>(
                15, TimeUnit.MINUTES
            ).setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Don't restart if already scheduled
                request
            )
            Log.d(TAG, "Background sync scheduled (every 15 minutes)")
        }

        /**
         * Cancel periodic background sync.
         * Called on logout.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Background sync cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting background sync...")

        return try {
            val user = AuthRepository.getCachedUser()
            val role = user?.role

            val synced = when (role) {
                com.gocavgo.ikuriye.data.dto.RoleDto.DRIVER -> syncDriverPackages()
                com.gocavgo.ikuriye.data.dto.RoleDto.CUSTOMER -> syncClientPackages()
                else -> {
                    Log.d(TAG, "No logged in user or unhandled role: $role, skipping sync")
                    true
                }
            }

            if (synced) {
                Log.d(TAG, "Background sync completed successfully (role=$role)")
                Result.success()
            } else if (runAttemptCount < 3) {
                Log.w(TAG, "Background sync failed to fetch — will retry")
                Result.retry()
            } else {
                Log.e(TAG, "Background sync failed after $runAttemptCount attempts")
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background sync failed: ${e.message}", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private suspend fun syncDriverPackages(): Boolean {
        return try {
            when (val result = PackageRepository.fetchMyPackages(
                page = 0,
                order = com.gocavgo.ikuriye.type.SortOrder.DESC
            )) {
                is com.gocavgo.ikuriye.data.FetchPackagesResult.Success -> {
                    withContext(Dispatchers.IO) {
                        PackageCache.saveDriver(result.page)
                    }
                    Log.d(TAG, "Driver packages synced: ${result.page.items.size} items")
                    true
                }
                is com.gocavgo.ikuriye.data.FetchPackagesResult.Error -> {
                    // Never overwrite a good cache with an error/empty result.
                    Log.e(TAG, "Failed to sync driver packages: ${result.message}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync driver packages: ${e.message}")
            false
        }
    }

    private suspend fun syncClientPackages(): Boolean {
        return try {
            when (val result = PackageRepository.fetchMyPackages(
                page = 0,
                order = com.gocavgo.ikuriye.type.SortOrder.DESC
            )) {
                is com.gocavgo.ikuriye.data.FetchPackagesResult.Success -> {
                    withContext(Dispatchers.IO) {
                        PackageCache.saveClient(result.page)
                    }
                    Log.d(TAG, "Client packages synced: ${result.page.items.size} items")
                    true
                }
                is com.gocavgo.ikuriye.data.FetchPackagesResult.Error -> {
                    // Never overwrite a good cache with an error/empty result.
                    Log.e(TAG, "Failed to sync client packages: ${result.message}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync client packages: ${e.message}")
            false
        }
    }
}
