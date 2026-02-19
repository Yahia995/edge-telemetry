import com.google.protobuf.gradle.*

plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("com.google.protobuf") version "0.9.4"
    application
}

group = "app.edge_telemetry"
version = "0.2.0"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor Server
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    implementation("io.ktor:ktor-server-cors:2.3.7")
    implementation("io.ktor:ktor-server-call-logging:2.3.7")
    implementation("io.ktor:ktor-server-status-pages:2.3.7")

    // gRPC — these three always travel together
    // grpc-netty-shaded bundles Netty so you don't get version conflicts with Ktor's Netty
    implementation("io.grpc:grpc-netty-shaded:1.68.1")
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")
    implementation("io.grpc:grpc-protobuf:1.68.1")

    // Protobuf Kotlin DSL builders (metric.cpu { usagePercent = ... })
    implementation("com.google.protobuf:protobuf-kotlin:4.29.0")

    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:2.3.7")
}

// ─── Protobuf code generation ───────────────────────────────────────────────
//
// The plugin reads .proto files from src/main/proto/ and generates:
//   - Java message classes         (protoc built-in)
//   - Kotlin DSL extension classes (kotlin builtin)
//   - Java gRPC stubs              (grpc plugin)
//   - Kotlin coroutine gRPC stubs  (grpckt plugin)
//
// Output lands in build/generated/source/proto/main/ and is added to the
// compile source set automatically.
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.29.0"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.68.1"
        }
        create("grpckt") {
            // jdk8@jar suffix selects the correct classifier for the fat jar
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
                create("grpckt")
            }
            task.builtins {
                // Generates Kotlin DSL builder extensions alongside the Java classes
                create("kotlin")
            }
        }
    }
}
// ────────────────────────────────────────────────────────────────────────────

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("app.edge_telemetry.ApplicationKt")
}
