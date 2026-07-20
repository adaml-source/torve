package com.torve.android.sync.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

class LanSyncDiscovery(
    context: Context,
    private val selfServiceNameHint: String,
    private val onServiceResolved: (LanResolvedService) -> Unit,
    private val onServiceLost: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false
    private var isRegistered = false
    private var registeredServiceName: String? = null

    // Serialize resolve calls — Android NSD only allows one resolveService at a time.
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile private var isResolving = false

    // Track services that failed resolution so we can retry them.
    private val pendingRetry = ConcurrentLinkedQueue<NsdServiceInfo>()

    fun start(port: Int) {
        registerService(port)
        startDiscovery()
    }

    fun stop() {
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
        discoveryListener = null
        isDiscovering = false
        isRegistered = false
        resolveQueue.clear()
        pendingRetry.clear()
        isResolving = false
    }

    /** Restart NSD discovery to pick up services that may have been missed. */
    fun restartDiscovery() {
        val oldListener = discoveryListener
        discoveryListener = null
        isDiscovering = false
        oldListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        // Small delay before restarting to let stop callback complete.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startDiscovery()
        }, 300)
    }

    /** Retry resolving any pending services that failed resolution. */
    fun retryPendingResolves() {
        while (pendingRetry.isNotEmpty()) {
            pendingRetry.poll()?.let { enqueueResolve(it) }
        }
    }

    private fun registerService(port: Int) {
        if (isRegistered) return
        val serviceInfo = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            serviceName = selfServiceNameHint.take(MAX_SERVICE_NAME_LENGTH)
            this.port = port
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                isRegistered = false
                onError("Service registration failed ($errorCode)")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                onError("Service unregister failed ($errorCode)")
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                isRegistered = true
                registeredServiceName = serviceInfo.serviceName
                Log.d(TAG, "Service registered: ${serviceInfo.serviceName}")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                isRegistered = false
            }
        }

        registrationListener = listener
        runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            onError("Service registration error: ${it.message}")
        }
    }

    private fun startDiscovery() {
        if (isDiscovering) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
                onError("Service discovery start failed ($errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
                onError("Service discovery stop failed ($errorCode)")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                isDiscovering = true
                Log.d(TAG, "Discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering = false
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType != SERVICE_TYPE) return
                val ownName = registeredServiceName ?: selfServiceNameHint
                if (service.serviceName == ownName) return
                Log.d(TAG, "Service found: ${service.serviceName}")
                enqueueResolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                onServiceLost(service.serviceName)
            }
        }
        discoveryListener = listener
        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            onError("Service discovery error: ${it.message}")
        }
    }

    private fun enqueueResolve(service: NsdServiceInfo) {
        resolveQueue.add(service)
        processResolveQueue()
    }

    @Synchronized
    private fun processResolveQueue() {
        if (isResolving) return
        val next = resolveQueue.poll() ?: return
        isResolving = true
        Log.d(TAG, "Resolving service: ${next.serviceName}")

        resolveServiceLegacy(next)

        // Safety timeout — if neither callback fires within 5 seconds, move on.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isResolving) {
                Log.w(TAG, "Resolve timeout for ${next.serviceName}, queuing retry")
                pendingRetry.add(next)
                isResolving = false
                processResolveQueue()
            }
        }, RESOLVE_TIMEOUT_MS)
    }

    private fun resolveServiceLegacy(service: NsdServiceInfo) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName} (error=$errorCode)")
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    // Another resolve is in progress — re-queue for retry.
                    pendingRetry.add(service)
                }
                isResolving = false
                processResolveQueue()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service resolved: ${serviceInfo.serviceName} → ${serviceInfo.host?.hostAddress}:${serviceInfo.port}")
                deliverResolved(serviceInfo)
                isResolving = false
                processResolveQueue()
            }
        }
        runCatching {
            nsdManager.resolveService(service, listener)
        }.onFailure {
            Log.w(TAG, "Resolve error for ${service.serviceName}", it)
            pendingRetry.add(service)
            isResolving = false
            processResolveQueue()
        }
    }

    private fun deliverResolved(serviceInfo: NsdServiceInfo) {
        val ownName = registeredServiceName ?: selfServiceNameHint
        if (serviceInfo.serviceName == ownName) return

        val hostAddress = serviceInfo.host?.hostAddress
        if (hostAddress == null || serviceInfo.port <= 0) return

        onServiceResolved(
            LanResolvedService(
                serviceName = serviceInfo.serviceName,
                host = hostAddress,
                port = serviceInfo.port,
            ),
        )
    }

    private companion object {
        const val TAG = "LanSyncDiscovery"
        const val SERVICE_TYPE = "_torve-sync._tcp."
        const val MAX_SERVICE_NAME_LENGTH = 63
        const val RESOLVE_TIMEOUT_MS = 5000L
    }
}
