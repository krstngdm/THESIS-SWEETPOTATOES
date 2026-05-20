plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-kapt")
}

android {
    namespace = "com.ai.growsight"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ai.growsight"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        mlModelBinding = true
    }
}

dependencies {

    implementation (libs.android.spinkit)
    implementation ("com.facebook.shimmer:shimmer:0.5.0")

    // ==========================================================
    // HTTP calls for weather API
    // ==========================================================

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // ==========================================================
    // TensorFlow Lite
    // ==========================================================

    implementation ("org.tensorflow:tensorflow-lite:2.17.0")
    implementation ("org.tensorflow:tensorflow-lite-support:0.5.0")
    implementation ("org.tensorflow:tensorflow-lite-gpu:2.17.0")

    // ==========================================================
    // AndroidX Core + Lifecycle
    // ==========================================================
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // ==========================================================
    // Jetpack Compose
    // ==========================================================
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // ==========================================================
    // AppCompat, UI, Material, Drawer, RecyclerView
    // ==========================================================
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // ==========================================================
    // CameraX
    // ==========================================================
    implementation("androidx.camera:camera-core:1.3.0")
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")

    // ==========================================================
    // Room Local Database
    // ==========================================================
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // ==========================================================
    // Other libraries you already had
    // ==========================================================
    implementation(libs.litertlm)
    implementation(libs.androidx.viewfinder.core)
    implementation(libs.androidx.media3.common.ktx)
    implementation(libs.tensorflow.lite.metadata)
    implementation(libs.androidx.cardview)

    // ==========================================================
    // Testing
    // ==========================================================
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    // ==========================================================
    // Debugging Tools
    // ==========================================================
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
