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

/**
 * Paramètre d'un réseau, du plus spécifique au plus général :
 * `clockin.<réseau>.<nom>` dans local.properties, puis la variable
 * d'environnement `CLOCKIN_<RÉSEAU>_<NOM>` (CI), puis l'ancienne clé
 * `clockin.<nom>` (devnet seulement), puis la valeur par défaut du réseau.
 */
fun setting(network: String, name: String, fallback: String): String {
    val env = "CLOCKIN_${network}_${name.replace(Regex("([A-Z])"), "_$1")}".uppercase()
    return localProperties.getProperty("clockin.$network.$name")
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty("clockin.$name")?.takeIf { network == "devnet" }
        ?: fallback
}

/** Valeurs par défaut de chaque réseau. Le program id est le même : le
 * programme est déployé sur mainnet avec la même clé que sur devnet. */
val networks = mapOf(
    "devnet" to mapOf(
        "rpcUrl" to "https://api.devnet.solana.com",
        "programId" to "ANT4AF24p1io1pmdNFKd9RKMStLCFi6WGbu81QoGzqN6",
        "skrMint" to "FKu7X2R2WVXDTaAQb6eE7xDtBJss9Yp3fYmBf6YyYAa5",
        "skrDecimals" to "9",
        "backendUrl" to "",
    ),
    "mainnet" to mapOf(
        // Le RPC public limite fort : en production, surcharger avec un RPC payant.
        "rpcUrl" to "https://api.mainnet-beta.solana.com",
        "programId" to "ANT4AF24p1io1pmdNFKd9RKMStLCFi6WGbu81QoGzqN6",
        "skrMint" to "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3",
        "skrDecimals" to "6",
        "backendUrl" to "",
    ),
)

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

        // Les noms .skr se résolvent toujours sur mainnet, quel que soit le réseau de l'app.
        buildConfigField("String", "IDENTITY_RPC_URL",
            "\"${localProperties.getProperty("clockin.identityRpcUrl") ?: "https://api.mainnet-beta.solana.com"}\"")
    }

    // Deux builds : `devnet` (« Moment dev », installable à côté) et `mainnet`
    // (production). Le réseau est figé dans l'APK : une build de production ne
    // peut pas parler à devnet.
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
                require(decimals.toIntOrNull() in 0..18) { "clockin.$network.skrDecimals doit être un entier entre 0 et 18" }
                buildConfigField("String", "NETWORK", "\"$network\"")
                buildConfigField("String", "RPC_URL", "\"${value("rpcUrl")}\"")
                buildConfigField("String", "PROGRAM_ID", "\"${value("programId")}\"")
                buildConfigField("String", "SKR_MINT", "\"${value("skrMint")}\"")
                // Décimales du mint avant sa première lecture : la chaîne fait foi ensuite.
                buildConfigField("int", "SKR_DECIMALS", decimals)
                buildConfigField("String", "BACKEND_URL", "\"${value("backendUrl")}\"")
            }
        }
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
