plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.andrerinas.wirelesshelper"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.andrerinas.wirelesshelper"
        minSdk = 21
        targetSdk = 36
        versionCode = 24
        versionName = "1.8.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            // Use defaults
        }

        create("release") {
            // Path relative to the 'app' directory of WirelessHelper
            storeFile = file("../wirelesshelper-release-key.jks")
            storePassword = System.getenv("HEADUNIT_KEYSTORE_PASSWORD")
            keyAlias = "headunit-revived" // Use the same alias as HURev
            keyPassword = System.getenv("HEADUNIT_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true // Enable shrinking for release
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Set artifact name globally
base {
    archivesName.set("com.andrerinas.wirelesshelper_${android.defaultConfig.versionName}")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation("com.google.android.material:material:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.linkedin.dexmaker:dexmaker:2.28.6")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("androidx.security:security-crypto:1.1.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
