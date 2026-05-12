# アーキテクチャ設計

> 本ドキュメントは映画館チケット予約システム (theater) の**設計の根拠**を一元化したもの。
> `spec.md` (要件) / `data_structure.md` (Firestore版・参考用) を Java + RDBMS に載せ替えた**正本**として扱う。

---

## 1. 技術スタック (確定事項)

| 種別 | 採用 | 理由 |
|---|---|---|
| 言語 | **Java 17** | 課題要件 (sealed / records / pattern matching for switch / text block を意図的に使用) |
| UI | **JavaFX 21** | デスクトップで座席UIを描きやすい / FXML+Controller で3人並列に画面分割しやすい / MVVM/Observerパターン例示が自然 |
| 永続化 | **SQLite 3.45+** (JDBC: `xerial/sqlite-jdbc`) | 1ファイルで提出が容易 / WALモードで同時読込◯ / 既定で `BEGIN/COMMIT` Serializable 相当の書込ロックを持ちACID検証に十分 |
| マイグレーション | **Flyway** (組込) | `V###__*.sql` で append-only に並べられチームでConflict回避しやすい |
| DIコンテナ | **自作** (`shared/di`) | 課題要件のGoFパターン (Singleton / Factory / Registry) を明示的に実装するため |
| Tx境界 | **自作 UnitOfWork + Command パターン** (`shared/tx`) | `@Transactional` 風の宣言を `TransactionalUseCase<C,R>` で表現 / GoF: Template Method + Command |
| ロギング | SLF4J + Logback | |
| ビルド | **Gradle 8 (Kotlin DSL)** | `libs.versions.toml` でバージョン一元管理、`testFixtures` 可、CI/lint連携が綺麗 |
| テスト | JUnit 5 + AssertJ + Mockito + ArchUnit + Awaitility + (任意で jqwik) | ACID/同時実行/アーキ違反を網羅 |
| Lint/Format | Spotless (google-java-format) + Checkstyle + ErrorProne + ArchUnit | `docs/ci_lint.md` 参照 |

> **JDK は Temurin 17** を全員に固定する (`.tool-versions` / `gradle/toolchains` で強制)。

---

## 2. レイヤ (DDD 戦術設計)

```
┌────────────────────────────────────────────┐
│  ui (JavaFX Controller / FXML / ViewModel)  │  ← Presentation
├────────────────────────────────────────────┤
│  application (UseCase / Command / Query)    │  ← Application Service
├────────────────────────────────────────────┤
│  domain (Aggregate / VO / DomainService /   │
│          Repository interface / Event)      │  ← 純粋。フレームワーク非依存
├────────────────────────────────────────────┤
│  infrastructure (JDBC Repo impl / Tx /      │
│                  EventBus / Mapper)         │  ← Adapter
└────────────────────────────────────────────┘
        ↑ 依存方向: ui → application → domain ← infrastructure
```

- `domain` は他のどのレイヤにも依存しない (= ピュアJava)
- `application` は `domain` のみに依存
- `infrastructure` は `domain` (の Repository interface) を実装する
- `ui` は `application` のみを呼ぶ (`domain` を直接触らない)

依存違反は **ArchUnit** で機械的に検出する (`docs/ci_lint.md`)。

---

## 3. 境界づけられたコンテキスト (Bounded Context)

| BC | 主な集約 | 責務 | 主担当 |
|---|---|---|---|
| **identity** | `User` | 登録/ログイン/ロール管理 | A |
| **catalog** | `Movie`, `Screen`(部屋), `Seat`(レイアウト), `Screening`(上映会) | マスタ管理・検索・スケジュール | B |
| **reservation** | `Reservation`, `SeatState` | 座席ホールド・期限切れ・ダブルブッキング防止 | C |
| **ordering** | `Order`, `Payment` | 確定/決済/返金 | C |
| **ticketing** | `Ticket` | 発券・利用済み・所有チケット表示 | A |

**コンテキスト間の通信は**:
1. 同期: `ui` から複数の `application` UseCase を順番に呼ぶ (Sagaを `ui` が orchestrate しない)。
2. 非同期: `domain_events_outbox` テーブル経由の **Outbox パターン**。確定ユースケース内で同一Txにイベントを書き、別ワーカーで配信。
3. 直接の domain クラス参照は **禁止** (パッケージ可視性で強制)。

### DTO 配置規約 (cross-BC vs 内部)

`application/` 配下の record / view 型は2系統に分けて配置する:

| 配置 | 用途 | 例 |
|---|---|---|
| `<bc>/application/*View.java` / `*Summary.java` | **自 BC 内部** の表示用 / UseCase 戻り値 | catalog の `MovieSummary` / `ScreeningDetailView` |
| `<bc>/application/dto/*.java` | **cross-BC 契約** (他 BC が消費する DTO) | reservation の `ConfirmedReservationView` (OR-04 が消費) |

cross-BC DTO は **`shared/kernel` の ID 型** および **primitive 型 (`Money` 等の VO 含む)** のみで構成し、他 BC の domain クラスを参照しない (= Anti-Corruption Layer)。

---

## 4. ディレクトリ / パッケージ構成

```
theater/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── config/
│   ├── checkstyle/checkstyle.xml
│   ├── spotless/header.txt
│   └── archunit/archunit.properties
├── docs/                          ← 設計ドキュメント (本ファイル含む)
├── CLAUDE.md                      ← Claude Code への指示
├── .github/workflows/
│   ├── ci.yml
│   └── lint.yml
├── src/
│   ├── main/
│   │   ├── java/com/theater/
│   │   │   ├── App.java                      ← エントリ (起動/DI登録の集約)
│   │   │   ├── shared/                       ← 共通カーネル
│   │   │   │   ├── di/                       ← Container, Module, Provider
│   │   │   │   ├── tx/                       ← UnitOfWork, TxManager, TransactionalUseCase
│   │   │   │   ├── eventbus/                 ← DomainEventBus, OutboxPublisher
│   │   │   │   ├── error/                    ← DomainException 階層
│   │   │   │   └── kernel/                   ← Identifier, Money, Result, Clock
│   │   │   ├── identity/        (Person A)
│   │   │   │   ├── domain/                   ← User, UserId, Email, PasswordHash, UserRepository
│   │   │   │   ├── application/              ← RegisterUserUseCase, LoginUseCase
│   │   │   │   ├── infrastructure/           ← JdbcUserRepository, BcryptHasher
│   │   │   │   └── ui/                       ← LoginController, LoginViewModel
│   │   │   ├── catalog/         (Person B)
│   │   │   │   ├── domain/                   ← Movie, Screen, Seat, Screening, *Repository
│   │   │   │   ├── application/
│   │   │   │   ├── infrastructure/
│   │   │   │   └── ui/                       ← HomeController, ScreeningListView
│   │   │   ├── reservation/     (Person C)
│   │   │   │   ├── domain/                   ← Reservation, SeatState, SeatHoldPolicy
│   │   │   │   ├── application/              ← HoldSeatsUseCase, ExtendHoldUseCase, ExpireHoldsJob
│   │   │   │   ├── infrastructure/
│   │   │   │   └── ui/                       ← SeatSelectController
│   │   │   ├── ordering/        (Person C)
│   │   │   │   ├── domain/                   ← Order, Payment, OrderRepository
│   │   │   │   ├── application/              ← CheckoutUseCase, RefundUseCase
│   │   │   │   ├── infrastructure/
│   │   │   │   └── ui/                       ← CheckoutController
│   │   │   └── ticketing/       (Person A)
│   │   │       ├── domain/                   ← Ticket, TicketRepository
│   │   │       ├── application/              ← IssueTicketsUseCase, MarkUsedUseCase, ListMyTicketsQuery
│   │   │       ├── infrastructure/
│   │   │       └── ui/                       ← TicketsController
│   │   └── resources/
│   │       ├── application.properties
│   │       ├── db/migration/
│   │       │   ├── V001__shared_identity.sql       (A)
│   │       │   ├── V010__catalog.sql               (B)
│   │       │   ├── V020__reservation.sql           (C)
│   │       │   ├── V030__ordering.sql              (C)
│   │       │   └── V040__ticketing.sql             (A)
│   │       ├── logback.xml
│   │       └── ui/fxml/
│   │           ├── login.fxml         (A)
│   │           ├── home.fxml          (B)
│   │           ├── seat_select.fxml   (C)
│   │           ├── checkout.fxml      (C)
│   │           └── tickets.fxml       (A)
│   ├── test/java/com/theater/...                   ← main をミラー
│   └── testFixtures/java/com/theater/testkit/      ← 共有Fixture/Builder
└── data/
    ├── theater.db          (gitignore)
    └── theater-test.db     (テストごとに削除)
```

> パッケージ可視性: `domain.*` の集約コンストラクタは package-private、生成は `*Factory` 経由。
> `infrastructure.*` の実装クラスも package-private にし、外部からは `domain` の interface でしか触れない。

---

## 5. 採用するGoF / その他デザインパターン

> 課題要件「BoF (GoF) のデザインパターンを1つ以上」は意図的に**複数**採用し、レポートで列挙できるようにする。

| パターン | 採用箇所 | 目的 |
|---|---|---|
| **Singleton** | `shared.di.Container` | アプリ起動時に1つだけ生成しグローバル参照 |
| **Factory Method** | `domain.*.*Factory` (例: `ReservationFactory.create()`) | 集約の不変条件を満たす形でしか生成させない |
| **Repository** (DDD) | `domain.*.*Repository` interface + `infrastructure.Jdbc*Repository` impl | 永続化の差し替え可能性 |
| **Unit of Work** | `shared.tx.UnitOfWork` | 1ユースケース=1Txの境界、複数Repo呼出の原子性 |
| **Command** | `application.*.*Command` (`record`) + `*UseCase.execute(Command)` | UseCase呼出を値オブジェクト化、ログ/再実行可能 |
| **Template Method** | `shared.tx.TransactionalUseCase<C,R>` | `execute(c) = within Tx { handle(c) }` の骨組み固定 |
| **Strategy** | `reservation.domain.SeatHoldPolicy` (期限/料金計算の差替) | 一般席/プレミアム/車椅子で価格戦略を切替 |
| **Observer** | `shared.eventbus.DomainEventBus` + JavaFX `Property` バインディング | 集約→UI更新、座席状態変化の通知 |
| **State** | `domain.*.*Status` (sealed interface) | Reservation/Order/Ticket の状態遷移を型で制約 |
| **Decorator** | `infrastructure.LoggingRepository` 等 (任意) | リポジトリにロギング/メトリクスを横断付与 |

JavaFX 側は **MVVM** (Model = ApplicationのDTO, ViewModel = `*ViewModel`, View = FXML+Controller)。

---

## 6. Java 17 機能の使いどころ (要件対応)

| 機能 | 用途例 |
|---|---|
| `record` | Command / Query / DTO / VO (`UserId`, `Money`) |
| `sealed interface` + `permits` | `SeatStatus`, `OrderStatus`, `Result<T,E>` (Ok/Err) |
| pattern matching for switch | 状態遷移ハンドラ (`switch (state) { case Hold h -> ...; case Sold s -> ...; }`) |
| pattern matching for `instanceof` | 例外/イベントの分岐 |
| text block | SQL リテラル (`""" SELECT ... """`) |
| `var` | ローカル変数 (リーダブルな範囲で) |

---

## 7. 起動シーケンス (`App.java`)

```
1. Logback init
2. Container.init() — Singleton 生成
3. 各 BC の Module.bindings(Container) を順に呼ぶ (registry append-only)
   - SharedModule → IdentityModule → CatalogModule → ReservationModule → OrderingModule → TicketingModule
4. Flyway.migrate() — V###__*.sql 全適用
5. ScheduledExecutor 起動
   - ExpireHoldsJob (1秒間隔) — HOLD 期限切れを EXPIRED に
   - OutboxPublisher (1秒間隔) — 未配信イベントを発火
6. JavaFX Application.launch() — LoginScreen を表示
```

---

## 8. 設定ファイル

`src/main/resources/application.properties` (例):

```
db.url=jdbc:sqlite:data/theater.db
db.foreignKeys=ON
db.journalMode=WAL
db.busyTimeoutMs=5000
hold.duration.minutes=10
job.expireHolds.intervalMs=1000
job.outbox.intervalMs=1000
```

テスト時は `application-test.properties` で `jdbc:sqlite::memory:` ではなく**ファイル一時DB** (`/tmp/theater-test-xxx.db`) を使う (`:memory:` は接続ごとに別DBになるため)。

---

## 9. 設計判断ログ (今後追記)

| 日付 | 決定 | 理由 |
|---|---|---|
| 2026-04-30 | 単一Gradleモジュール採用 (multi-module不採用) | 学校課題スコープでは package 境界 + ArchUnit で十分。multi-module は overkill |
| 2026-04-30 | SQLite採用 / 同時書込は `BEGIN IMMEDIATE` で直列化 | 課題提出の容易さ優先 + 単一プロセス想定で十分 |
| 2026-04-30 | Outbox パターン採用 | チケット発券時の「Order確定」と「在庫減算イベント発火」を atomic にしたい |
