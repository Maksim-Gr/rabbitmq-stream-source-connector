import java.time.LocalDateTime

val buildTimestamp: String = LocalDateTime.now().toString()

plugins {
    `java-library`
    `maven-publish`

    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"

    id("com.palantir.git-version") version "1.0.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.1.0"
}

group = "com.github.maksimgr"
version = "0.1.0"

repositories {
    mavenCentral()
    maven(url = "https://packages.confluent.io/maven/")
    maven(url = "https://jitpack.io")
}

configurations.all {
    resolutionStrategy.capabilitiesResolution {
        withCapability("org.lz4:lz4-java") {
            select("org.lz4:lz4-java:1.10.1")
        }
    }
}

dependencies {
    compileOnly("org.apache.kafka:connect-api:4.2.0")
    implementation("org.apache.kafka:kafka-clients:4.2.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation("org.slf4j:slf4j-api:2.0.6")
    implementation("ch.qos.logback:logback-classic:1.5.13")

    implementation("commons-validator:commons-validator:1.10.1")

    implementation("com.rabbitmq:stream-client:0.16.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("org.mockito:mockito-core:5.22.0")

    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:kafka:1.21.4")
    testImplementation("org.testcontainers:rabbitmq:1.21.4")

    testImplementation("com.github.christophschubert:cp-testcontainers:v0.2.1")
    testImplementation("org.sourcelab:kafka-connect-client:4.0.3")
    testImplementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    testImplementation("com.rabbitmq:amqp-client:5.18.0")
    testImplementation("org.apache.kafka:kafka-clients:4.2.0")
    testCompileOnly("org.apache.kafka:connect-api:4.2.0")
}

kotlin {
    jvmToolchain(17)
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

    exclude("**/*IntegrationTest*")
}

val integrationTest by tasks.registering(Test::class) {
    useJUnitPlatform()
    reports.html.required.set(true)

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }

    include("**/*IntegrationTest*")
}

tasks.jar {
    val runtimeJars = configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }
    from(runtimeJars.map(::zipTree))

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            mapOf(
                "Build-Timestamp" to buildTimestamp,
                // "Build-Revision" to details.gitHash,
                // "Build-Is-Clean" to details.isCleanTag,
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version.toString(),
            ),
        )
    }
}

tasks.register("generateVersion") {
    description = "Create a version.properties file in the main resources output folder"

    doLast {
        val resourcesDir = sourceSets.main.get().output.resourcesDir
        requireNotNull(resourcesDir) { "main.resourcesDir is null; can't write version.properties" }

        resourcesDir.mkdirs()

        file("$resourcesDir/version.properties").writeText(
            """
            Build-Timestamp=$buildTimestamp
            Connector-Version=${project.version}
            Implementation-Title=${project.name}
            Implementation-Version=${project.version}
            """.trimIndent(),
        )
    }
}

tasks.build {
    dependsOn("generateVersion")
}
