plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.jarvis.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.jarvis.app"
        minSdk = 28
        targetSdk = 35
        versionCode = providers.gradleProperty("jarvisVersionCode").orNull?.toInt() ?: 6
        versionName = providers.gradleProperty("jarvisVersionName").orNull ?: "0.5.1"
        require(versionCode!! > 0)
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9._-]+)?").matches(versionName!!)) { "Invalid jarvisVersionName: $versionName" }
        val updateRepository = providers.gradleProperty("jarvisUpdateRepository").orNull ?: "sreyasts/jarvis-android-releases"
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(updateRepository))
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            val requested = providers.gradleProperty("jarvisAbi").orNull
            require(requested == null || requested in setOf("arm64-v8a", "x86_64"))
            abiFilters += requested?.let(::listOf) ?: listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild { cmake { cppFlags += "-O3"; arguments += "-DCMAKE_BUILD_TYPE=Release" } }
    }
    buildTypes {
        release {
            if (providers.gradleProperty("jarvisUseLegacySigningKey").orNull == "true") {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    ndkVersion = "27.0.12077973"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    androidResources { noCompress += "onnx" }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":voice"))
    implementation(project(":safety"))
    implementation(files(rootProject.file("libs/sherpa-onnx-2.0.1.aar")))
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
