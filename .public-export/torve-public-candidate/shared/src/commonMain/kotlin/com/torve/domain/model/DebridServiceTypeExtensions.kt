package com.torve.domain.model

val DebridServiceType.apiValue: String
    get() = when (this) {
        DebridServiceType.REAL_DEBRID -> "real_debrid"
        DebridServiceType.ALL_DEBRID -> "all_debrid"
        DebridServiceType.PREMIUMIZE -> "premiumize"
        DebridServiceType.TORBOX -> "torbox"
    }
