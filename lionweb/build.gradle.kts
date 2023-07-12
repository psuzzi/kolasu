plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("maven-publish")
    id("signing")
    id("org.jetbrains.dokka")
}

val lionwebVersion = extra["lionwebVersion"]
val kotlinVersion = extra["kotlinVersion"]
val clikt_version = extra["clikt_version"]

dependencies {
    implementation("com.github.ajalt.clikt:clikt:$clikt_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")
    implementation("io.lionweb.lioncore-java:lioncore-java-core-fat:$lionwebVersion")
    api(project(":core"))
}

publishing {
    addSonatypeRepo(project)
    addPublication("kolasu_lionweb", "Integration of Kolasu with LIonWeb", project)
}

signing {
    sign(publishing.publications["kolasu_lionweb"])
}
