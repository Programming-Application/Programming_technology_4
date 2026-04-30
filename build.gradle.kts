import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    `java-library`
    `java-test-fixtures`
    application
    jacoco
    checkstyle
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.javafx)
}

group = "com.theater"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

application {
    mainClass.set("com.theater.App")
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.graphics")
}

dependencies {
    implementation(libs.sqlite.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.awaitility)

    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.assertj.core)
    testFixturesImplementation(libs.sqlite.jdbc)
    testFixturesImplementation(libs.flyway.core)

    errorprone(libs.errorprone.core)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-Xlint:-processing"))
    options.errorprone {
        disableWarningsInGeneratedCode.set(true)
        excludedPaths.set(".*/build/generated/.*")
    }
}

// テスト用ビルドではErrorProneの厳格チェックを少し緩める (Mockitoのargsとか拾われがち)
tasks.named<JavaCompile>("compileTestJava").configure {
    options.errorprone.disable("UnusedVariable", "MissingSummary")
}
tasks.named<JavaCompile>("compileTestFixturesJava").configure {
    options.errorprone.disable("UnusedVariable", "MissingSummary")
}

spotless {
    java {
        target("src/**/*.java")
        // 2sp indent / 4sp continuation の google-java-format 標準スタイル。
        // Lambda / fluent assertion / builder 系で AOSP より圧倒的に読みやすい。
        googleJavaFormat("1.22.0")
        importOrder()
        removeUnusedImports()
        formatAnnotations()
        endWithNewline()
        trimTrailingWhitespace()
        toggleOffOn()
    }
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
        ktlint()
    }
    format("misc") {
        target("*.md", ".gitignore", ".editorconfig")
        targetExclude("docs/**", "CLAUDE.md", "README.md")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

checkstyle {
    toolVersion = "10.14.2"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

tasks.test {
    useJUnitPlatform {
        excludeTags("slow")
    }
    systemProperty("java.io.tmpdir", layout.buildDirectory.dir("tmp").get().asFile.path)
    finalizedBy(tasks.jacocoTestReport)
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

tasks.register<Test>("slowTest") {
    description = "Run concurrency / slow tagged tests"
    group = "verification"
    useJUnitPlatform { includeTags("slow", "concurrency") }
    shouldRunAfter(tasks.test)
}

jacoco {
    toolVersion = "0.8.11"
}
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        // 雛形段階では緩めに。実装が乗ってきたら段階的に上げる。
        rule {
            limit { minimum = "0.00".toBigDecimal() }
        }
    }
}

tasks.check {
    dependsOn("spotlessCheck", "jacocoTestCoverageVerification")
}

// JavaFX を runtime classpath に乗せて `./gradlew run` を直で動かす
tasks.named<JavaExec>("run") {
    jvmArgs = listOf("-Xshare:auto")
}
