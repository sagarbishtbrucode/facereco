plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.test.facescan"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.test.facescan"
        minSdk = 24
        targetSdk = 35
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation ("com.google.mlkit:face-detection:16.1.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    implementation ("com.google.code.gson:gson:2.8.6")
    implementation ("com.google.guava:guava:27.1-android")
    implementation ("com.google.android.odml:image:1.0.0-beta1")
    implementation("com.google.mlkit:camera:16.0.0-beta3")
    implementation ("androidx.camera:camera-camera2:1.0.0-SNAPSHOT")
    implementation ("androidx.camera:camera-lifecycle:1.0.0-SNAPSHOT")
    implementation ("androidx.camera:camera-view:1.0.0-SNAPSHOT")
}