import org.jetbrains.kotlin.konan.properties.Properties

version = 1

android {
    compileSdk = 34
    defaultConfig {
        minSdk = 21
        buildFeatures { buildConfig = true }
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())
        buildConfigField("String", "Akwam_API", "\"${properties.getProperty("Akwam_API", "https://ak.sv")}\"")
    }
}

cloudstream {
    language = "en"
    authors  = listOf("kim20598")
    status   = 1
    tvTypes  = listOf("Anime", "OVA", "Movie", "TvSeries")
    iconUrl  = "https://www.google.com/s2/favicons?domain=ak.sv&sz=%size%"
    isCrossPlatform = false
}
