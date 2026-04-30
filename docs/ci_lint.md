# CI / Lint / Format 設定

> 3人並列開発で**機械的に Conflict と品質劣化を防ぐ**ための CI / Lint / Format 設定。
> Person A が Sprint 0 でセットアップ → 以降 main ブランチで強制。

---

## 1. ゴール

- フォーマットを **完全自動化** (誰の手元でも同じ結果)
- レイヤ違反 / BC 越境参照を **機械検出**
- ACID/Tx を含むテストの **再現性** を CI 上で保証
- PR が緑にならないとマージ不可 (Branch protection)

---

## 2. ツール選定

| カテゴリ | 採用 | 用途 |
|---|---|---|
| Format | **Spotless + google-java-format** | Java の整形 (タブ/import順/末尾改行) |
| Style Lint | **Checkstyle** | 命名/構造/不要 import |
| Code Smell | **PMD** (任意) | 重複/不要 catch / cyclomatic |
| Compile-time Lint | **Error Prone** (任意) | NullAway / FormatString / etc. |
| Architecture | **ArchUnit** | レイヤ違反 / BC越境参照 |
| Test Coverage | **JaCoCo** | 行/分岐カバレッジ (BC毎しきい値) |
| CI | **GitHub Actions** | PR / push でビルド+テスト |
| Pre-commit (任意) | **lefthook** or **husky** | ローカルで spotlessApply 強制 |

---

## 3. Gradle 設定 (Kotlin DSL)

### 3.1 `gradle/libs.versions.toml`

```toml
[versions]
java = "17"
javafx = "21.0.5"
junit = "5.10.2"
assertj = "3.25.3"
mockito = "5.11.0"
sqlite = "3.45.1.0"
flyway = "10.10.0"
slf4j = "2.0.12"
logback = "1.5.3"
archunit = "1.3.0"
awaitility = "4.2.1"
spotless = "6.25.0"
checkstyle = "10.14.2"
errorprone = "2.27.0"
errorpronePlugin = "3.1.0"
jacoco = "0.8.11"

[libraries]
sqlite-jdbc = { module = "org.xerial:sqlite-jdbc", version.ref = "sqlite" }
flyway-core = { module = "org.flywaydb:flyway-core", version.ref = "flyway" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
logback-classic = { module = "ch.qos.logback:logback-classic", version.ref = "logback" }

junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
mockito-core = { module = "org.mockito:mockito-core", version.ref = "mockito" }
mockito-junit = { module = "org.mockito:mockito-junit-jupiter", version.ref = "mockito" }
archunit-junit5 = { module = "com.tngtech.archunit:archunit-junit5", version.ref = "archunit" }
awaitility = { module = "org.awaitility:awaitility", version.ref = "awaitility" }

[plugins]
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
errorprone = { id = "net.ltgt.errorprone", version.ref = "errorpronePlugin" }
javafx = { id = "org.openjfx.javafxplugin", version = "0.1.0" }
```

### 3.2 `build.gradle.kts` (要所)

```kotlin
plugins {
    java
    `java-library`
    `java-test-fixtures`
    jacoco
    checkstyle
    application
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.javafx)
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

application { mainClass = "com.theater.App" }

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
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.awaitility)

    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.assertj.core)

    errorprone("com.google.errorprone:error_prone_core:${libs.versions.errorprone.get()}")
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        disableWarningsInGeneratedCode = true
        // 必要に応じて check を on/off
        check("MissingOverride", net.ltgt.gradle.errorprone.CheckSeverity.ERROR)
    }
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        googleJavaFormat("1.22.0").aosp()
        importOrder()
        removeUnusedImports()
        endWithNewline()
        trimTrailingWhitespace()
        toggleOffOn()
    }
    kotlinGradle { ktlint() }
}

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configFile  = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

tasks.test {
    useJUnitPlatform {
        // 通常はslowを除外。CIの slow ジョブで個別に実行
        excludeTags("slow")
    }
    systemProperty("java.io.tmpdir", layout.buildDirectory.dir("tmp").get().asFile.path)
    finalizedBy(tasks.jacocoTestReport)
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.register<Test>("slowTest") {
    useJUnitPlatform { includeTags("slow", "concurrency") }
    shouldRunAfter(tasks.test)
}

jacoco { toolVersion = libs.versions.jacoco.get() }
tasks.jacocoTestReport {
    reports {
        xml.required = true
        html.required = true
    }
}
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule { limit { minimum = "0.70".toBigDecimal() } }                // 全体70%
        rule {
            element = "PACKAGE"
            includes = listOf("com.theater.reservation.*", "com.theater.ordering.*")
            limit { minimum = "0.85".toBigDecimal() }                     // Tx重量パッケージ85%
        }
    }
}

tasks.check { dependsOn("spotlessCheck", "jacocoTestCoverageVerification") }
```

---

## 4. Checkstyle 設定 (要点)

`config/checkstyle/checkstyle.xml` (抜粋):
- `LineLength` 120
- `Indentation` 4 / `LeftCurly` EOL
- `UnusedImports`, `RedundantImport`
- `MissingJavadocMethod` は public のみ警告 (private はoff)
- `MagicNumber` を on (`-1, 0, 1, 2` は除外)
- パッケージ命名 `^com\.theater(\.[a-z][a-z0-9]*)+$`

---

## 5. ArchUnit (`testing.md` に既出)

レイヤと BC の依存ルールは `testing.md` §6 のものを `LayerArchTest.java` に保存し **CIで毎回実行**。

---

## 6. GitHub Actions

### 6.1 `.github/workflows/ci.yml`

```yaml
name: ci
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17', cache: gradle }
      - uses: gradle/actions/setup-gradle@v3
      - run: ./gradlew --no-daemon spotlessCheck checkstyleMain checkstyleTest
      - run: ./gradlew --no-daemon test
      - run: ./gradlew --no-daemon jacocoTestCoverageVerification
      - if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: |
            build/reports/tests/test
            build/reports/jacoco/test/html
            build/reports/checkstyle

  slow:
    needs: build-test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17', cache: gradle }
      - uses: gradle/actions/setup-gradle@v3
      - run: ./gradlew --no-daemon slowTest
```

### 6.2 `.github/workflows/lint.yml`

```yaml
name: lint
on: [pull_request]
jobs:
  lint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17', cache: gradle }
      - run: ./gradlew --no-daemon spotlessCheck checkstyleMain checkstyleTest
```

### 6.3 Branch Protection (Web UI で設定)

- `main` への直 push 禁止
- 必須チェック: `ci / build-test`, `lint / lint`
- 1 名以上の review approval 必須
- Linear history 強制 (rebase merge のみ)

---

## 7. Pre-commit (任意, 推奨)

`lefthook.yml`:
```yaml
pre-commit:
  parallel: true
  commands:
    spotless:
      glob: "*.java"
      run: ./gradlew spotlessApply
      stage_fixed: true
    checkstyle:
      run: ./gradlew checkstyleMain checkstyleTest
```

---

## 8. ローカル開発のおすすめコマンド

```
./gradlew spotlessApply       # 整形
./gradlew check               # lint + test + coverage
./gradlew test --tests "*ConcurrencyTest"  # 同時実行テストだけ繰り返す
./gradlew slowTest            # 重いテスト
./gradlew run                 # JavaFX 起動
```

---

## 9. .gitignore (主要)

```
.gradle/
build/
out/
.idea/
.vscode/
*.iml
data/*.db
data/*.db-*
/tmp/
*.log
```
