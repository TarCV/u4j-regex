plugins {
    id("java")
}

val icuVersion = 74.2
group = "com.github.TarCV.u4jregex"
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