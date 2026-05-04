# theater — 映画館チケット予約システム

[![ci](https://github.com/Programming-Application/Programming_technology_4/actions/workflows/ci.yml/badge.svg)](https://github.com/Programming-Application/Programming_technology_4/actions/workflows/ci.yml)
[![lint](https://github.com/Programming-Application/Programming_technology_4/actions/workflows/lint.yml/badge.svg)](https://github.com/Programming-Application/Programming_technology_4/actions/workflows/lint.yml)

プログラミング技術4 (自由課題) — 映画館のチケット予約・購入を JavaFX デスクトップで行うシステム。
**3人のチームで Bounded Context 単位に並列開発** する前提で、DDD 戦術設計と ACID/同時実行テストを主眼に据える。

> 仕様の正本は [`docs/spec.md`](docs/spec.md)、Claude Code (および人間) への作業指示は [`CLAUDE.md`](CLAUDE.md)。

---

## 何ができるか (MVP 完成条件)

1. 新規登録 → ログイン
2. 上映会一覧から目的の上映を選択
3. 座席を選んで HOLD (期限10分)
4. 合計金額を確認 → 決済 → チケット発行
5. 保有チケットを確認
6. **同一座席を同時に取り合っても必ず1人だけ成功 (ダブルブッキング不可)**
7. **決済失敗時に注文/チケット/座席状態のいずれも変化しない (Atomicity)**

詳細: [`docs/spec.md`](docs/spec.md) §5

---

## 技術スタック

| 領域 | 採用 |
|---|---|
| 言語 | Java 17 (Temurin) |
| UI | JavaFX 21 (FXML + MVVM) |
| DB | SQLite 3.45+ (WAL, ACID) |
| マイグレーション | Flyway |
| アーキテクチャ | DDD 戦術設計 + 自作 DI / UnitOfWork (GoF パターン顕在化) |
| テスト | JUnit 5 + AssertJ + Mockito + ArchUnit + Awaitility |
| Lint / Format | Spotless (google-java-format) + Checkstyle + ArchUnit + ErrorProne |
| ビルド | Gradle 8.7 (Kotlin DSL) |
| CI | GitHub Actions |

---

## クイックスタート

### 必要なもの
- Java 17 (Temurin 推奨。`./gradlew` がツールチェインを自動取得します)
- Git

### セットアップ

```bash
git clone https://github.com/Programming-Application/Programming_technology_4.git theater
cd theater
./gradlew build
```

### よく使うコマンド

```bash
./gradlew run                     # JavaFX アプリを起動
./gradlew check                   # lint + 全テスト + カバレッジ
./gradlew spotlessApply           # 整形 (コミット前に必ず)
./gradlew test                    # 通常テスト (slow / concurrency 除外)
./gradlew slowTest                # 同時実行 / @Tag("slow") のみ
./gradlew test --tests "*ConcurrencyTest"
```

---

## ディレクトリ構成

```
theater/
├── CLAUDE.md                      Claude Code への作業指示
├── README.md                      (このファイル)
├── build.gradle.kts               Gradle ビルド設定 (Kotlin DSL)
├── settings.gradle.kts            foojay-resolver で JDK 17 自動取得
├── gradle/libs.versions.toml      ライブラリ・プラグインバージョン一元管理
├── config/
│   ├── checkstyle/checkstyle.xml  スタイル lint
│   └── archunit/archunit.properties
├── .github/
│   ├── workflows/{ci.yml, lint.yml}
│   └── PULL_REQUEST_TEMPLATE.md
├── docs/                          設計ドキュメント (下記マップ参照)
└── src/
    ├── main/java/com/theater/
    │   ├── App.java               アプリ起動 (DI登録 / Flyway / JavaFX)
    │   ├── shared/                共通カーネル (DI / Tx / EventBus / Kernel)
    │   ├── identity/              認証 BC                     (Person A)
    │   ├── catalog/               マスタ BC                   (Person B)
    │   ├── reservation/           予約 BC                     (Person C)
    │   ├── ordering/              注文・決済 BC                (Person C)
    │   └── ticketing/             チケット BC                  (Person A)
    │     (各 BC は domain / application / infrastructure / ui の4層)
    ├── main/resources/
    │   ├── application.properties
    │   ├── db/migration/V001..V040 (担当別の番号区画)
    │   └── ui/fxml/*.fxml
    ├── test/java/com/theater/...
    └── testFixtures/java/com/theater/testkit/  共有 Fixture
```

---

## ドキュメントマップ

| ファイル | 役割 |
|---|---|
| [`docs/spec.md`](docs/spec.md) | **「何を作るか」のマスタ** (機能カタログ / MVP DoD / NFR / スコープ外) |
| [`docs/architecture.md`](docs/architecture.md) | DDD レイヤ・5 BC・dir 構造・**採用 GoF 10種**・起動シーケンス |
| [`docs/features.md`](docs/features.md) | 30 ユースケース (UC ID 付き) と画面・契約 |
| [`docs/data_model.md`](docs/data_model.md) | SQLite スキーマ正本 / 不変条件 / **ダブルブッキング4層防御** / Checkout 12-step Tx |
| [`docs/task_split.md`](docs/task_split.md) | **3人分担とファイル所有権マップ** / Sprint 計画 / Conflict 回避運用 |
| [`docs/testing.md`](docs/testing.md) | **ACID テストテンプレート** (A/C/I/D) / 💰 金銭整合チェックリスト / ArchUnit |
| [`docs/ci_lint.md`](docs/ci_lint.md) | Gradle / Spotless / Checkstyle / ArchUnit / GHA 詳細 |
| [`docs/data_structure.md`](docs/data_structure.md) | 旧 Firestore 案 (legacy reference; 正本は `data_model.md`) |
| [`docs/todo.md`](docs/todo.md) | 元の課題要件 |
| [`CLAUDE.md`](CLAUDE.md) | Claude Code (および人間) への作業指示 |

---

## 開発ルール (要点 — 詳細は CLAUDE.md と docs/)

- **新規/変更の書込ユースケースには必ず4種類のテストを書く**: Unit / Repository / Tx / Concurrency
- **金銭が絡む UC は `docs/testing.md` §3 の 💰 チェックリスト全項目** を担保
- レイヤ越境・BC 越境参照は **ArchUnit が CI で機械検出** (違反は merge 不可)
- ファイル所有権は [`docs/task_split.md`](docs/task_split.md) §2 のマップに従う。共有ファイル (`build.gradle.kts` / `App.java` / `application.properties`) を触るときは PR description に明記
- マイグレーション番号は `V0xx=shared` / `V1xx=catalog` / `V2xx=reservation` / `V3xx=ordering` / `V4xx=ticketing`
- ブランチ戦略: **`main` ← `develop` ← `feat/*`** ([`CONTRIBUTING.md`](CONTRIBUTING.md))
  - `feat/<owner>/<bc>/<topic>` 例: `feat/c/reservation/hold-seats` → base は **develop**
  - `develop` → `main` はリリース PR (fast-forward / linear history)
  - `main` は protected (直 push 禁止 / 必須 CI / 1+ approval / force push禁止)
- コミット前に `./gradlew spotlessApply` (lefthook 任意)

---

## ライセンス / 由来

学校課題 (プログラミング技術4) として作成。デプロイは行わない。
