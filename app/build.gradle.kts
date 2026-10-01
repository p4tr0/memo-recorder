plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "io.github.p4tr0.voicememo"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.p4tr0.voicememo"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key lives outside the repo: its path and password come from ~/.gradle/gradle.properties.
    // Every update must be signed with it, or Android refuses to install over the existing app.
    val releaseKeystore = providers.gradleProperty("VOICEMEMO_KEYSTORE").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.gradleProperty("VOICEMEMO_KEYSTORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("VOICEMEMO_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("VOICEMEMO_KEYSTORE_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // Never falls back to the debug key: without the release key, packaging fails (checkReleaseKey).
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Lets MigrationTestHelper read the exported schemas in unit tests. Robolectric only sees the app's merged
    // debug assets (not test assets), so they go in debug builds only, never in release.
    sourceSets["debug"].assets.srcDir("$projectDir/schemas")

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // android.util.Log in plain JVM tests becomes a no-op instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Version-bump nags shouldn't break the build; upgrades are done deliberately.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

// Fails a release build without the release key, instead of producing an unsigned or debug-signed APK.
val hasReleaseKey = providers.gradleProperty("VOICEMEMO_KEYSTORE").isPresent
val checkReleaseKey = tasks.register("checkReleaseKey") {
    val present = hasReleaseKey
    doLast {
        if (!present) {
            throw GradleException(
                "No release key: set VOICEMEMO_KEYSTORE, VOICEMEMO_KEYSTORE_PASSWORD and VOICEMEMO_KEY_ALIAS " +
                    "in ~/.gradle/gradle.properties (see the release skill)."
            )
        }
    }
}
tasks.matching { it.name == "packageRelease" }.configureEach { dependsOn(checkReleaseKey) }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    // Robolectric reflects into FileDescriptor internals, which Java 21 encapsulates.
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.room.testing)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
