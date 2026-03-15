plugins {
    java
    application
}

group = "si.uni-lj.fri.wier"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // HTML Parsing & Web
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.seleniumhq.selenium:selenium-java:4.18.1")
    
    // Crawling Utilities
    implementation("com.github.crawler-commons:crawler-commons:1.3")
    implementation("org.netpreserve:urlcanon:0.4.0")
    
    // Performance & Logic
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("io.github.bucket4j:bucket4j-core:8.7.0")
    
    // Database
    implementation("org.postgresql:postgresql:42.7.2")
    
    // Logging (Standard SLF4J + Logback)
    implementation("org.slf4j:slf4j-api:2.0.12")
    implementation("ch.qos.logback:logback-classic:1.5.3")

    // Testing
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("si.uni-lj.fri.wier.crawler.Main")
}

tasks.test {
    useJUnitPlatform()
}
