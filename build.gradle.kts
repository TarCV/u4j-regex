plugins {
    id("java")
    `maven-publish`
}

val icuVersion = 74.2
group = "com.github.TarCV"
version = "$icuVersion-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(8)
    }
}

dependencies {
    implementation("com.ibm.icu:icu4j:$icuVersion")
    testImplementation("junit:junit:4.13.2")
    testImplementation("pl.pragmatists:JUnitParams:1.1.1")
}

tasks {
    withType<AbstractArchiveTask>().configureEach {
        // Settings for reproducibility
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        fileMode = "644".toInt(8)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}