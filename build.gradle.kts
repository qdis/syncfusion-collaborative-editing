plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.5.7"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "ai.apps"
version = "0.0.1-SNAPSHOT"
description = "syncfusion-collaborative-editing"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://jars.syncfusion.com/repository/maven-public")
    }
}


dependencies {
    implementation("com.syncfusion:syncfusion-ej2-wordprocessor:+")
    implementation("com.syncfusion:syncfusion-ej2-spellchecker:+")
    implementation("com.syncfusion:syncfusion-docio:+")
    implementation("com.syncfusion:syncfusion-licensing:+")
    implementation("com.syncfusion:syncfusion-javahelper:+")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("com.h2database:h2")

    // AWS SDK for S3 (MinIO compatibility)
    implementation(platform("software.amazon.awssdk:bom:2.24.9"))
    implementation("software.amazon.awssdk:s3")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
