import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.irvin.dacrohx"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.irvin.dacrohx"
        minSdk = 31
        targetSdk = 36
        // Cada compilación en GitHub sube la versión sola (v1.0.N)
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionCode = run
        versionName = "1.0.$run"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("irvin") {
            storeFile = file("irvin.jks")
            storePassword = "irvindacrohx"
            keyAlias = "irvin"
            keyPassword = "irvindacrohx"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("irvin")
        }
        debug {
            signingConfig = signingConfigs.getByName("irvin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            pickFirsts.add("**/liblitertlm_jni.so")
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.addAll("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Cerebro offline: LiteRT-LM (Gemma 4 en el teléfono)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.0")

    // Oído offline para "Oye Irvin"
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}

configurations.all {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
}
