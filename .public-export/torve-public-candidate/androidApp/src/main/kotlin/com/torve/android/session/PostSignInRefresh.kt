package com.torve.android.session

import android.content.Context
import com.torve.android.catalog.CatalogWarmupWorker
import com.torve.android.sync.TraktSyncWorker
import com.torve.presentation.session.AccountSessionCoordinator
import com.torve.presentation.session.RestorePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

object PostSignInRefresh {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastEnqueueAtMs = AtomicLong(0L)

    fun enqueue(context: Context, force: Boolean = false) {
        enqueueStartupCacheWarmup(context, force)
    }

    fun enqueueStartupCacheWarmup(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val previous = lastEnqueueAtMs.get()
        if (!force && now - previous < DUPLICATE_ENQUEUE_WINDOW_MS) return
        if (force) {
            lastEnqueueAtMs.set(now)
        } else if (!lastEnqueueAtMs.compareAndSet(previous, now)) {
            return
        }

        val appContext = context.applicationContext
        android.util.Log.i(
            "PostSignInRefresh",
            "enqueueStartupCacheWarmup force=$force lightweight=true missingOnly=true visibleProgress=false",
        )
        CatalogWarmupWorker.refreshNow(
            context = appContext,
            lightweight = true,
            visibleProgress = false,
            missingOnly = true,
        )
        TraktSyncWorker.syncNow(appContext)
    }

    fun enqueueAfterCredentialImport(context: Context) {
        enqueueCredentialImportRefresh(context, force = true)
    }

    fun enqueueFullRefreshAfterCredentialImport(context: Context) {
        enqueueFullAppRefresh(context, reason = "credential_import_manual", force = true)
    }

    fun enqueueContentWarmupAfterAccountActivation(context: Context) {
        enqueueActivatedContentWarmup(context, force = true)
    }

    fun enqueueAfterAccountRestore(
        context: Context,
        accountSessionCoordinator: AccountSessionCoordinator,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val current = accountSessionCoordinator.restoreProgress.value
            if (current.phase.isFinished()) {
                enqueuePostRestoreWarmup(appContext, force = true)
                return@launch
            }
            withTimeoutOrNull(POST_SIGN_IN_RESTORE_WAIT_MS) {
                accountSessionCoordinator.restoreProgress.first { progress ->
                    progress.phase.isFinished()
                }
            }
            enqueuePostRestoreWarmup(appContext, force = true)
        }
    }

    private fun enqueuePostRestoreWarmup(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val previous = lastEnqueueAtMs.get()
        if (!force && now - previous < DUPLICATE_ENQUEUE_WINDOW_MS) return
        if (force) {
            lastEnqueueAtMs.set(now)
        } else if (!lastEnqueueAtMs.compareAndSet(previous, now)) {
            return
        }

        val appContext = context.applicationContext
        android.util.Log.i(
            "PostSignInRefresh",
            "enqueuePostRestoreWarmup force=$force credentialImport=true",
        )
        CatalogWarmupWorker.refreshAfterCredentialImport(appContext)
        TraktSyncWorker.syncNow(appContext)
    }

    private fun enqueueCredentialImportRefresh(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val previous = lastEnqueueAtMs.get()
        if (now - previous < DUPLICATE_ENQUEUE_WINDOW_MS) return
        if (force) {
            lastEnqueueAtMs.set(now)
        } else if (!lastEnqueueAtMs.compareAndSet(previous, now)) {
            return
        }

        val appContext = context.applicationContext
        android.util.Log.i("PostSignInRefresh", "enqueueCredentialImportRefresh force=$force catalogOnly=true")
        CatalogWarmupWorker.refreshAfterCredentialImport(appContext)
        TraktSyncWorker.syncNow(appContext)
    }

    private fun enqueueActivatedContentWarmup(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val previous = lastEnqueueAtMs.get()
        if (now - previous < DUPLICATE_ENQUEUE_WINDOW_MS) return
        if (force) {
            lastEnqueueAtMs.set(now)
        } else if (!lastEnqueueAtMs.compareAndSet(previous, now)) {
            return
        }

        val appContext = context.applicationContext
        android.util.Log.i("PostSignInRefresh", "enqueueActivatedContentWarmup force=$force catalogOnly=true stagedEpg=true")
        CatalogWarmupWorker.refreshAfterCredentialImport(appContext)
    }

    private fun enqueueFullAppRefresh(
        context: Context,
        reason: String,
        force: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        val previous = lastEnqueueAtMs.get()
        if (!force && now - previous < DUPLICATE_ENQUEUE_WINDOW_MS) return
        if (force) {
            lastEnqueueAtMs.set(now)
        } else if (!lastEnqueueAtMs.compareAndSet(previous, now)) {
            return
        }

        val appContext = context.applicationContext
        android.util.Log.i("PostSignInRefresh", "enqueueFullAppRefresh reason=$reason force=$force")
        CatalogWarmupWorker.refreshNow(
            context = appContext,
            lightweight = false,
            visibleProgress = true,
            missingOnly = false,
        )
        TraktSyncWorker.syncNow(appContext)
    }

    private fun RestorePhase.isFinished(): Boolean =
        this == RestorePhase.COMPLETED || this == RestorePhase.COMPLETED_WITH_ERRORS

    private const val POST_SIGN_IN_RESTORE_WAIT_MS = 3L * 60L * 1000L
    private const val DUPLICATE_ENQUEUE_WINDOW_MS = 5_000L
}
