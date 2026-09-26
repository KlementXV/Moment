import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/** Paramètres de déploiement : ils vivent dans local.properties, jamais dans le
 * dépôt. Une valeur absente reste vide et l'app le signale à l'exécution. */
fun setting(key: String, fallback: String): String {
    val properties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    return "\"" + (properties.getProperty(key) ?: fallback) + "\""
}

android {
    namespace = "com.clockin.hackathon"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.clockin.hackathon"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "IDENTITY_RPC_URL", setting("clockin.identityRpcUrl", "https://api.mainnet-beta.solana.com"))
        buildConfigField("String", "RPC_URL", setting("clockin.rpcUrl", "https://api.devnet.solana.com"))
        buildConfigField("String", "PROGRAM_ID", setting("clockin.programId", "ANT4AF24p1io1pmdNFKd9RKMStLCFi6WGbu81QoGzqN6"))
        buildConfigField("String", "SKR_MINT", setting("clockin.skrMint", ""))
        // Décimales du mint avant sa première lecture : 9 pour le mint de test
        // devnet, 6 pour le vrai SKR. La chaîne fait foi dès qu'elle répond.
        buildConfigField("int", "SKR_DECIMALS", setting("clockin.skrDecimals", "9").trim('"')
            .also { require(it.toIntOrNull() in 0..18) { "clockin.skrDecimals doit être un entier entre 0 et 18" } })
        buildConfigField("String", "BACKEND_URL", setting("clockin.backendUrl", ""))
        buildConfigField("String", "NETWORK", setting("clockin.network", "devnet"))
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}


dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Solana Mobile Stack: Mobile Wallet Adapter + Kotlin client SDK
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.2.0")
    implementation("com.solanamobile:web3-solana:0.3.1")
    implementation("com.solanamobile:rpc-core:0.2.11")
    implementation("io.github.funkatronics:multimult:0.2.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
