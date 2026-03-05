import java.time.LocalDateTime

val currentDateTime: LocalDateTime = LocalDateTime.now()

plugins {
    `java-library`
    kotlin("jvm") version "2.3.0"
    id("com.palantir.git-version") version "1.0.0"
    kotlin("plugin.serialization") version "1.5.0"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
    `maven-publish`
}

group = "com.github.maksimgr"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven { url = uri("https://packages.confluent.io/maven/") }
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("org.apache.kafka:connect-api:3.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.slf4j:slf4j-api:2.0.6")
    implementation("commons-validator:commons-validator:1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("io.confluent:kafka-connect-avro-converter:7.3.0")
    testImplementation("com.github.christophschubert:cp-testcontainers:v0.2.1")
    testImplementation("org.testcontainers:kafka:1.19.6")
    testImplementation("org.sourcelab:kafka-connect-client:4.0.3")
    testImplementation("io.github.microutils:kotlin-logging-jvm:3.0.4")
    implementation("ch.qos.logback:logback-classic:1.5.13")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2") // JUnit Jupiter
    testRuntimeOnly("org.junit.platform:junit-platform-launcher") // JUnit Platform Launcher
    implementation("com.rabbitmq:stream-client:0.16.0")
    testImplementation("org.mockito:mockito-core:5.5.0")
    testImplementation("org.testcontainers:junit-jupiter:1.19.0")
    testImplementation("org.testcontainers:rabbitmq:1.19.0")
    testImplementation("com.rabbitmq:amqp-client:5.18.0")
    testImplementation(kotlin("test"))
}
ktlint {
    version.set("1.2.1")
    android.set(false)
    outputColorName.set("RED")
    verbose.set(true)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

tasks.test {
    useJUnitPlatform()
    reports.html.required.set(true)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
    exclude("**/*IntegrationTest/.*")
}

val integrationTest by tasks.registering(Test::class) {
    useJUnitPlatform()
    reports.html.required.set(true)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
    include("**/*IntegrationTest/.*")
}
kotlin {
    jvmToolchain(17)
}
tasks.jar {
    val dependencies =
        configurations
            .runtimeClasspath
            .get()
            .map(::zipTree)
    from(dependencies)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            mapOf(
                "Build-Timestamp" to currentDateTime,
//                "Build-Revision" to details.gitHash,
//                "Build-Is-Clean" to details.isCleanTag,
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
            ),
        )
    }
}

tasks.register("generateVersion") {
    description = "Create a version properties file in the build folder"
    doLast {
        sourceSets.main
            .get()
            .output.resourcesDir
            ?.mkdirs()
        file("${sourceSets.main.get().output.resourcesDir}/version.properties").writeText(
            """
            Build-Timestamp=$currentDateTime
            
            Connector-Version=${project.version},
            "Implementation-Title" to ${project.name},
            "Implementation-Version" to ${project.version},
            """.trimIndent(),
        )
    }
}
tasks.build {
    dependsOn(":generateVersion")
}
