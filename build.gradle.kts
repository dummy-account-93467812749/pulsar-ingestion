plugins {
    kotlin("jvm") version "1.9.20" apply false
    java apply false
}

allprojects {
    group = "com.example.pulsar"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java")

    java {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.10.0"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "11"
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
