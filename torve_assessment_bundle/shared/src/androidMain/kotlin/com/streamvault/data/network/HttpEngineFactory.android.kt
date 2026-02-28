package com.streamvault.data.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.android.Android

actual fun platformHttpEngine(): HttpClientEngineFactory<*> = Android
