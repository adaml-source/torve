package com.torve.di

import com.torve.domain.transfer.SecretsTransferProtocol
import com.torve.domain.transfer.TransferCryptoEngine
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Builds the iOS-side Koin module for credential transfer.
 *
 * Swift owns the [TransferCryptoEngine] implementation (CryptoKit-backed
 * — Curve25519 + HKDF-SHA256 + AES-GCM). It hands the engine instance
 * to this builder, which binds it as a singleton plus constructs and
 * binds the [SecretsTransferProtocol] on top. The shared sender + receiver
 * VM factories already live in [sharedModule] and consume both
 * dependencies via [org.koin.core.scope.Scope.get].
 *
 * Kept as a tiny Kotlin function rather than a Swift `Koin_coreModule`
 * dance because [SecretsTransferProtocol]'s default-arg constructor
 * doesn't survive the Kotlin/Native ObjC bridge cleanly.
 */
fun buildIosTransferModule(engine: TransferCryptoEngine): Module = module {
    single<TransferCryptoEngine> { engine }
    single { SecretsTransferProtocol(engine = get()) }
}
