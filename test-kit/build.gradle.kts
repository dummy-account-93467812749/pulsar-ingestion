plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.20"
    `java-library`
}

dependencies {
    implementation(project(":common"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
}
