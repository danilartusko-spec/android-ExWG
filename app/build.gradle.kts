plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val nexusAbiSplits = providers.gradleProperty("nexusAbiSplits").map(String::toBoolean).getOrElse(true)
val exWgVersion = rootProject.file("VERSION").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0.0"

android {
    namespace = "com.nexus.client"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nexus.client"
        minSdk = 23
        targetSdk = 36
        versionCode = 29
        versionName = exWgVersion
        manifestPlaceholders["exWgAppLabel"] = "Ex WG"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("ru", "en")
    }

    signingConfigs.getByName("debug") {
        enableV1Signing = true
        enableV2Signing = true
        enableV3Signing = true
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug"; manifestPlaceholders["exWgAppLabel"] = "Ex WG • тест" }
        release {
            // Keep release diagnostics readable and avoid an aggressively stripped test build.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    splits {
        abi {
            isEnable = nexusAbiSplits
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main").assets.srcDir("../core-go/mobile/roots")
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        // The Go engine compresses very well. Side-loaded APKs should remain
        // compact; Android extracts the library during installation.
        jniLibs.useLegacyPackaging = true
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(files("../core-go/libs/nexuscore.aar"))
    implementation(project(":core"))
    implementation(project(":tunnel"))
    // Keep the dependency set conservative; the application baseline is API 23.
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
