/*
 * Copyright (c) 2026, PocketMS contributors
 */

plugins {
    kotlin("js")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlin.stdlib.js)
    implementation(libs.kotlinx.serialization.json)

    implementation(projects.remoteClient)
    implementation(projects.storeDao)
    implementation(projects.storeManager)

    testImplementation(libs.kotlin.test.js)
}

val compilerType: org.jetbrains.kotlin.gradle.plugin.KotlinJsCompilerType by ext
kotlin {
    js(compilerType) {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
    }
}
