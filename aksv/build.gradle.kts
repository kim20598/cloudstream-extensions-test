plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://raw.githubusercontent.com/recloudstream/cloudstream/maven")
}

dependencies {
    compileOnly("com.github.recloudstream:cloudstream:3.4.0")
    implementation(kotlin("stdlib-jdk8"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}
