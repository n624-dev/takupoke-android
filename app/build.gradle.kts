plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.serialization"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "jp.n624.takupoke.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "jp.n624.takupoke.android"
        minSdk = 29
        targetSdk = 36
        versionCode = providers.environmentVariable("TKPK_VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.environmentVariable("TKPK_VERSION_NAME").orNull ?: "0.1.0"
        testInstrumentationRunner = "jp.n624.takupoke.android.OfflineRunner"
    }
    signingConfigs {
        create("distribution") {
            providers.environmentVariable("TKPK_KEYSTORE").orNull?.let { storeFile = file(it) }
            storePassword = providers.environmentVariable("TKPK_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("TKPK_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("TKPK_KEY_PASSWORD").orNull
        }
    }
    buildTypes { release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"); if (providers.environmentVariable("TKPK_KEYSTORE").isPresent) signingConfig = signingConfigs.getByName("distribution") } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.datastore:datastore:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Avoid the PDF port's old transitive cryptography versions.
    implementation("org.bouncycastle:bcprov-jdk15to18:1.86")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.86")
    implementation("org.bouncycastle:bcutil-jdk15to18:1.86")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
