plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.ytrear.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.ytrear.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 12
        versionName = "1.2"
    }

    // Xiaomi's rear-screen service gates music widgets on an exact package-name map.
    // Keep the normal app untouched and provide a separate proof build using an
    // allowlisted third-party music identity that is absent from the test phone.
    flavorDimensions += "identity"
    productFlavors {
        create("normal") {
            dimension = "identity"
        }
        create("allowlisted") {
            dimension = "identity"
            applicationId = "com.luna.music"
            versionNameSuffix = "-allowlisted"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
