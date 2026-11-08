plugins { id("com.android.library") }

// Official AWG config model. The official Go 3.1 engine is linked exactly once
// in core-go/nexuscore.aar alongside the packet router.
android {
    namespace = "org.amnezia.awg.tunnel"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { disable += setOf("LongLogTag", "NewApi") }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.collection:collection:1.4.5")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    testImplementation("junit:junit:4.13.2")
}
