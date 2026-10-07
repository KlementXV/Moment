import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Stable local version; tagged CI builds override it with -PversionTag=vX.Y.Z.
val versionTag = providers.gradleProperty("versionTag").orNull ?: "v0.3.1"
val versionMatch = Regex("^v(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$").matchEntire(versionTag)
    ?: error("versionTag must be a stable tag such as v0.3.0 (pre-releases need a distinct versionCode scheme)")
val (versionMajor, versionMinor, versionPatch) = versionMatch.destructured.toList().map(String::toInt)
require(versionMinor <= 999 && versionPatch <= 999) { "versionTag minor and patch must be at most 999" }
val androidVersionCode = versionMajor.toLong() * 1_000_000 + versionMinor * 1_000 + versionPatch
require(androidVersionCode in 1..2_100_000_000) { "versionTag produces an invalid Android versionCode" }

val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf(String::isNotBlank)

fun setting(network: String, name: String, fallback: String): String {
    val env = "MOMENT_${network}_${name.replace(Regex("([A-Z])"), "_$1")}".uppercase()
    return localProperties.getProperty("moment.$network.$name")
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty("moment.$name")?.takeIf { network == "devnet" }
        ?: fallback
}

val networks = mapOf(
    "devnet" to mapOf(
        "rpcUrl" to "https://api.devnet.solana.com",
        "programId" to "ANT4AF24p1io1pmdNFKd9RKMStLCFi6WGbu81QoGzqN6",
        "skrMint" to "FKu7X2R2WVXDTaAQb6eE7xDtBJss9Yp3fYmBf6YyYAa5",
        "skrDecimals" to "9",
        "backendUrl" to "https://moment-dev.noodler.cc",
    ),
    "mainnet" to mapOf(
        "rpcUrl" to "https://api.mainnet-beta.solana.com",
        "programId" to "ANT4AF24p1io1pmdNFKd9RKMStLCFi6WGbu81QoGzqN6",
        "skrMint" to "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3",
        "skrDecimals" to "6",
        "backendUrl" to "",
    ),
)

android {
    namespace = "com.klementxv.moment"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.klementxv.moment"
        minSdk = 26
        targetSdk = 37
        versionCode = androidVersionCode.toInt()
        versionName = versionTag.removePrefix("v")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "IDENTITY_RPC_URL",
            "\"${localProperties.getProperty("moment.identityRpcUrl") ?: "https://api.mainnet-beta.solana.com"}\"")
    }

    flavorDimensions += "network"
    productFlavors {
        networks.forEach { (network, defaults) ->
            create(network) {
                dimension = "network"
                if (network == "devnet") {
                    applicationIdSuffix = ".dev"
                    versionNameSuffix = "-devnet"
                }
                fun value(name: String) = setting(network, name, defaults.getValue(name))
                val decimals = value("skrDecimals")
                require(decimals.toIntOrNull() in 0..18) { "moment.$network.skrDecimals doit être un entier entre 0 et 18" }
                buildConfigField("String", "NETWORK", "\"$network\"")
                buildConfigField("String", "RPC_URL", "\"${value("rpcUrl")}\"")
                buildConfigField("String", "PROGRAM_ID", "\"${value("programId")}\"")
                buildConfigField("String", "SKR_MINT", "\"${value("skrMint")}\"")
                buildConfigField("int", "SKR_DECIMALS", decimals)
                buildConfigField("String", "BACKEND_URL", "\"${value("backendUrl")}\"")
            }
        }
    }

    signingConfigs {
        create("ciRelease") {
            if (releaseKeystorePath != null) {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf(String::isNotBlank)
                    ?: error("ANDROID_KEYSTORE_PASSWORD is required for signed release builds")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")?.takeIf(String::isNotBlank)
                    ?: error("ANDROID_KEY_ALIAS is required for signed release builds")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")?.takeIf(String::isNotBlank)
                    ?: error("ANDROID_KEY_PASSWORD is required for signed release builds")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Seeker is arm64; other ABIs would add ~100 MB of ONNX Runtime native libs.
            ndk { abiFilters += "arm64-v8a" }
            if (releaseKeystorePath != null) signingConfig = signingConfigs.getByName("ciRelease")
        }
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

    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.2.0")
    implementation("com.solanamobile:web3-solana:0.3.1")
    implementation("com.solanamobile:rpc-core:0.2.11")
    implementation("io.github.funkatronics:multimult:0.2.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
