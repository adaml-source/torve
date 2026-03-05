package com.streamvault.android.sync.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

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
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering = false
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType != SERVICE_TYPE) return
                val ownName = registeredServiceName ?: selfServiceNameHint
                if (service.serviceName == ownName) return
                resolveService(service)
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

    private fun resolveService(service: NsdServiceInfo) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName} ($errorCode)")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val ownName = registeredServiceName ?: selfServiceNameHint
                if (serviceInfo.serviceName == ownName) return
                val hostAddress = serviceInfo.host?.hostAddress ?: return
                if (serviceInfo.port <= 0) return
                onServiceResolved(
                    LanResolvedService(
                        serviceName = serviceInfo.serviceName,
                        host = hostAddress,
                        port = serviceInfo.port,
                    ),
                )
            }
        }
        runCatching {
            nsdManager.resolveService(service, listener)
        }.onFailure {
            Log.w(TAG, "Resolve error for ${service.serviceName}", it)
        }
    }

    private companion object {
        const val TAG = "LanSyncDiscovery"
        const val SERVICE_TYPE = "_torve-sync._tcp."
        const val MAX_SERVICE_NAME_LENGTH = 63
    }
}
