import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.munchkin.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.munchkin.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 108
        versionName = "2.21.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Backend endpoint. Overridable at build time so a dev or staging server can
        // be targeted without editing source, e.g.
        //   ./gradlew assembleDebug -PmunchkinScheme=ws -PmunchkinHost=10.0.2.2
        // (10.0.2.2 is the host machine as seen from the Android emulator.)
        val serverScheme = (project.findProperty("munchkinScheme") as String?) ?: "wss"
        val serverHost = (project.findProperty("munchkinHost") as String?) ?: "munchking-sirpepo.duckdns.org"
        val serverPort = (project.findProperty("munchkinPort") as String?) ?: "8765"
        buildConfigField("String", "SERVER_SCHEME", "\"$serverScheme\"")
        buildConfigField("String", "SERVER_HOST", "\"$serverHost\"")
        buildConfigField("int", "SERVER_PORT", serverPort)
    }
    
    signingConfigs {
        create("release") {
            // Load credentials from local.properties (gitignored)
            val localPropsFile = rootProject.file("local.properties")
            val localProperties = Properties()
            if (localPropsFile.exists()) {
                localProperties.load(localPropsFile.inputStream())
            }
            storeFile = file("../munchkin.keystore")
            storePassword = localProperties.getProperty("KEYSTORE_STORE_PASSWORD") ?: System.getenv("KEYSTORE_STORE_PASSWORD") ?: ""
            keyAlias = localProperties.getProperty("KEYSTORE_KEY_ALIAS") ?: System.getenv("KEYSTORE_KEY_ALIAS") ?: ""
            keyPassword = localProperties.getProperty("KEYSTORE_KEY_PASSWORD") ?: System.getenv("KEYSTORE_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Code was already shrunk but resources were not; enabling this
            // strips unused resources from the release APK.
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // English and French had drifted 28 strings behind Spanish. That surfaces as
        // untranslated text mid-screen rather than as any kind of failure, so nothing
        // caught it. This makes the drift fail the build instead.
        //
        // checkOnly narrows the lint task to these two checks. The project has ~210
        // other lint warnings and 1 pre-existing error; turning them all fatal here
        // would mean fixing an unrelated backlog before this gate could land, so they
        // are deliberately left for their own pass rather than silenced.
        checkOnly += listOf("MissingTranslation", "ExtraTranslation")
        error += listOf("MissingTranslation", "ExtraTranslation")
        abortOnError = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    
    // Compose Core
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    
    // Activity & Lifecycle
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    
    // AppCompat for per-app language support
    implementation("androidx.appcompat:appcompat:1.6.1")
    
    
    // Ktor WebSocket client.
    // The ktor-server-* artifacts were dropped along with the embedded LAN-host
    // mode: nothing under app/src imports io.ktor.server, so they were shipping
    // an unused server stack inside the APK. Same for the content-negotiation
    // plugins — this client hand-rolls its JSON via kotlinx.serialization.
    val ktorVersion = "2.3.8"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")

    // Serialization (cbor removed — unused)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    
    // QR Code Generation
    implementation("com.google.zxing:core:3.5.3")
    
    // CameraX for QR Scanning
    val cameraXVersion = "1.3.1"
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    
    // ML Kit for barcode scanning
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    
    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // Accompanist Permissions (for camera in QR scanner)
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    
    // Encrypted SharedPreferences.
    // 1.1.0 is stable now; this held the session token and JWT on an alpha build.
    implementation("androidx.security:security-crypto:1.1.0")

    // Core KTX
    implementation("androidx.core:core-ktx:1.12.0")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    // ktor-server-test-host removed: no test imports io.ktor, and it pulled the
    // Ktor server stack back into the test classpath.
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}







