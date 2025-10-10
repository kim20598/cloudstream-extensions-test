import org.jetbrains.kotlin.konan.properties.Properties

version = 1

android {
    compileSdk = 34
    defaultConfig {
        minSdk = 21
        targetSdk = 34
        buildFeatures { buildConfig = true }
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())
        buildConfigField("String", "AKSV_API", "\"${properties.getProperty("AKSV_API", "https://ak.sv")}\"")
    }
}

dependencies {
    implementation("com.google.firebase:firebase-crashlytics-buildtools:3.0.6")
}

cloudstream {
    language     = "en"
    authors      = listOf("kim20598")
    status       = 1
    tvTypes      = listOf("Anime", "OVA")
    iconUrl      = "https://www.google.com/s2/favicons?domain=ak.sv&sz=%size%"
    isCrossPlatform = false
}
