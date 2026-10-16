import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing details live outside the repo. Copy keystore.properties.template to
// keystore.properties and fill it in; without it, release builds simply go unsigned rather
// than failing, so a fresh clone still builds.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "earth.dipstick.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "earth.dipstick.app"
        // 28 lets the app install on Android 9 for preview/streaming testing. Note that
        // saving recordings relies on MediaStore + scoped storage, which is an Android 10
        // behaviour - on API 28 the write will likely fail until a legacy storage path and
        // WRITE_EXTERNAL_STORAGE are added.
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Left off deliberately for the first releases: the UVC stack reaches into its
            // own classes from JNI, and an app bundle already splits the native libraries
            // per ABI, which is where nearly all the size is. Revisit once there is a
            // release build that has been exercised on hardware.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }

    // The UVC library ships prebuilt .so for these ABIs only.
    packaging {
        jniLibs { useLegacyPackaging = false }
        // jmDNS brings its own metadata that collides with the other dependencies'.
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    implementation("com.herohan:UVCAndroid:1.0.13")

    // Advertises a chosen <name>.local for the stream. Android's own mDNS responder only
    // publishes a generic "Android-N.local" that an app cannot rename.
    implementation("org.jmdns:jmdns:3.5.9")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
