import org.jetbrains.kotlin.konan.properties.Properties

version = 1        // bump when you release

android {
    buildFeatures { buildConfig = true }
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())
        // ak.sv needs no secrets → leave empty or add later
        buildConfigField("String", "AKSV_KEY", "\"\"")
    }
}

cloudstream {
    language    = "en"
    authors     = listOf("kim20598")
    status      = 1
    tvTypes     = listOf("Anime", "OVA")
    iconUrl     = "https://raw.githubusercontent.com/kim20598/cloudstream-extensions-test/master/aksv/icon.png"
    isCrossPlatform = true
}
