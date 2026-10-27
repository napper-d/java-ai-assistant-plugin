plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "edu.teamc"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    implementation("dev.langchain4j:langchain4j:1.7.1")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("org.apache.poi:poi-ooxml:6.0.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("io.github.cdimascio:dotenv-java:3.2.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
    // IDEA 2025.2's JUnit 5 session listener still references JUnit 4 TestCase.
    testRuntimeOnly("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea("2025.2.4")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    // Course RAG now contributes a project settings page.
    buildSearchableOptions = true
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    runIde {
        // Keep IDE diagnostics in idea.log without duplicating SDK/network warnings in Run.
        // Restore console diagnostics with: runIde -PideConsoleLog=true
        systemProperty("idea.log.console", providers.gradleProperty("ideConsoleLog").orElse("false").get())
    }
    test {
        useJUnitPlatform()
        systemProperty("java.awt.headless", "true")
    }
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

val ragProjectRoot = providers.gradleProperty("ragProjectRoot").orElse(layout.projectDirectory.asFile.absolutePath)
val ragEnvDir = providers.gradleProperty("ragEnvDir").orElse(layout.projectDirectory.asFile.absolutePath)
val ragFiles = providers.gradleProperty("ragFiles").orElse("")
val ragQuery = providers.gradleProperty("ragQuery").orElse("")
val ragMaxResults = providers.gradleProperty("ragMaxResults").orElse("5")

tasks.register<JavaExec>("ragImport") {
    group = "rag"
    description = "Register original PDF/PPTX paths separated by | without copying files."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("edu.teamc.courseaiassistant.rag.RagCli")
    args("import", ragProjectRoot.get(), ragEnvDir.get(), ragFiles.get())
}
tasks.register<JavaExec>("ragUnregister") {
    group = "rag"
    description = "Remove original paths from the registry without deleting the files or saved index."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("edu.teamc.courseaiassistant.rag.RagCli")
    args("unregister", ragProjectRoot.get(), ragEnvDir.get(), ragFiles.get())
}
tasks.register<JavaExec>("ragIndex") {
    group = "rag"
    description = "Rebuild the local course index using the configured embedding API."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("edu.teamc.courseaiassistant.rag.RagCli")
    args("index", ragProjectRoot.get(), ragEnvDir.get())
}
tasks.register<JavaExec>("ragSearch") {
    group = "rag"
    description = "Search the persisted course index."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("edu.teamc.courseaiassistant.rag.RagCli")
    args("search", ragProjectRoot.get(), ragEnvDir.get(), ragQuery.get(), ragMaxResults.get())
}

tasks.register("exportCheckClasspath") {
    group = "verification"
    val classpath = sourceSets.main.get().runtimeClasspath
    val outputFile = layout.buildDirectory.file("ui-check-classpath.txt")
    inputs.files(classpath)
    outputs.file(outputFile)
    doLast { outputFile.get().asFile.writeText(classpath.asPath) }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
