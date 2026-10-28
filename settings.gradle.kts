pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://4thline.org/m2")
        // NewPipeExtractor is published via JitPack. Used by the desktop
        // trailer overlay to resolve YouTube watch URLs into direct stream
        // URLs that VLC can play without the unreliable youtube.luac script.
        maven("https://jitpack.io")
    }
}

rootProject.name = "StreamVault"
include(":shared")
include(":androidApp")
include(":desktopApp")
