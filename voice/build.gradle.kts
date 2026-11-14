plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "dev.jarvis.voice"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    compileOnly(files(rootProject.file("libs/sherpa-onnx-2.0.1.aar")))
    testImplementation("junit:junit:4.13.2")
}
