import java.time.LocalDateTime

val buildTimestamp: String = LocalDateTime.now().toString()

plugins {
    `java-library`
    jacoco
    kotlin("jvm") version "2.4.0"

    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

group = "com.github.maksimgr"
version = "0.3.0"

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
    // Confluent test helpers pull an older Confluent-versioned kafka (e.g. 7.0.1-ccs)
    // that lacks classes referenced by the 4.3.1 Connect API (e.g. PluginMetrics).
    // Pin the Kafka artifacts to the version this connector targets.
    resolutionStrategy.force(
        "org.apache.kafka:kafka-clients:4.3.1",
        "org.apache.kafka:connect-api:4.3.1",
    )
}

dependencies {
    // connect-api and kafka-clients are provided by the Kafka Connect runtime, so
    // they must not be bundled into the plugin jar (avoids classloader conflicts).
    compileOnly("org.apache.kafka:connect-api:4.3.1")
    compileOnly("org.apache.kafka:kafka-clients:4.3.1")

    // Only depend on the slf4j API; the Connect runtime supplies the logging backend.
    implementation("org.slf4j:slf4j-api:2.0.18")

    implementation("com.rabbitmq:stream-client:1.8.0")

    // The Connect APIs are compileOnly for the plugin, so tests must bring them in
    // explicitly (at runtime) to exercise the connector code.
    testImplementation("org.apache.kafka:connect-api:4.3.1")
    testImplementation("org.apache.kafka:kafka-clients:4.3.1")

    // Logging backend for local test runs only (not shipped in the plugin jar).
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.38")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("org.mockito:mockito-core:5.23.0")

    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:kafka:1.21.4")
    testImplementation("org.testcontainers:rabbitmq:1.21.4")

    testImplementation("com.github.christophschubert:cp-testcontainers:v0.2.1")
    testImplementation("org.sourcelab:kafka-connect-client:4.0.5")
    testImplementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    testImplementation("com.rabbitmq:amqp-client:5.34.0")
}

kotlin {
    jvmToolchain(17)
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

ktlint {
    version.set("1.2.1")
    android.set(false)
    outputColorName.set("RED")
    verbose.set(true)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN_GROUP_BY_FILE)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
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
    finalizedBy(tasks.jacocoTestReport)
}

val integrationTest by tasks.registering(Test::class) {
    // A manually registered Test task does not inherit the test source set, so wire it
    // up explicitly; otherwise the task is NO-SOURCE and silently runs nothing.
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath

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

// Ensure version.properties is generated into the main resources output before the
// classes are assembled, so it is present on the classpath for both the jar and tests.
tasks.named("generateVersion") {
    mustRunAfter("processResources")
}

tasks.named("classes") {
    dependsOn("generateVersion")
}

tasks.build {
    dependsOn("generateVersion")
}
