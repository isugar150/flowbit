plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "com.flowbit"
version = "0.0.1-SNAPSHOT"
description = "Flowbit backend"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    // Flyway migrates over JDBC; the application itself talks to the database over R2DBC.
    implementation("org.springframework:spring-jdbc")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // Argon2 implementation behind Spring Security's Argon2PasswordEncoder
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    // Decodes uploads and re-encodes them as WebP (bundles the cwebp/dwebp binaries)
    implementation("com.sksamuel.scrimage:scrimage-core:4.6.8")
    implementation("com.sksamuel.scrimage:scrimage-webp:4.6.8")
    // LDAP sign-in. The SDK is blocking, so every call runs on Dispatchers.IO (LdapDirectory).
    implementation("com.unboundid:unboundid-ldapsdk")
    // Verifies the ID token of an OpenID Connect sign-in (OidcClient).
    implementation("org.springframework.security:spring-security-oauth2-jose")
    // Mail notifications. Jakarta Mail is blocking, so every send runs on Dispatchers.IO (Mailer).
    implementation("org.springframework.boot:spring-boot-starter-mail")
    // Signs the service account assertion for Firebase Cloud Messaging (FcmClient).
    implementation("com.nimbusds:nimbus-jose-jwt")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.postgresql:r2dbc-postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-data-r2dbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-r2dbc")
    // An SMTP server inside the test JVM
    testImplementation("com.icegreen:greenmail:2.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// A check run from the command line while the application is running from the IDE must not
// overwrite the class files that application was started from: classes it loads later would then
// come from a newer build and fail in odd ways. Such runs set FLOWBIT_BUILD_DIR to build elsewhere.
System.getenv("FLOWBIT_BUILD_DIR")?.takeIf { it.isNotBlank() }?.let { layout.buildDirectory.set(file(it)) }
