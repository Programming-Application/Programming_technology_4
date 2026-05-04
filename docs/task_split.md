# タスク分割 (3人並列開発, Conflict回避設計)

> 3人 (A / B / C) で並列開発する前提で、**ファイル単位の所有権**を明示することで Git Conflict を構造的に防ぐ設計。
> 共有ファイルは「append-only」or 「ownership rotation」or 「PR で1つに直列化」のいずれかで運用する。

---

## 1. 担当割当 (Big Picture)

| 担当 | 主担当 BC | 主担当横断 | 主な納品物 |
|---|---|---|---|
| **🅰 Person A** | identity / ticketing | shared (DI/Tx/EventBus/Kernel), App.java, ロギング, テスト共通基盤 | ログイン〜マイチケットの縦串 + 全員が依存する基盤 |
| **🅱 Person B** | catalog (movie / screen / seat / screening) | 管理用シードSQL / ホーム画面のFXML | 一覧/検索/詳細 + マスタ系シード |
| **🅲 Person C** | reservation / ordering | MockPaymentGateway / 座席UI / 決済UI | 予約〜決済の縦串 (本案件最重量) |

> A は「**プラットフォーム + 軽量機能**」, B は「**マスタ寄り**」, C は「**Tx重量級**」。
> A は Sprint 0 で shared を完成させ B/C をブロックしないこと。

---

## 2. ファイル所有権マップ

> ◎=所有(編集主), ○=参照のみ可, △=PRレビュー必須(共有編集), ×=触らない

| パス | A | B | C | 共有ルール |
|---|:-:|:-:|:-:|---|
| `build.gradle.kts` | ◎ | △ | △ | ライブラリ追加は **Slack/Issue で予告 → A が PR でまとめる** |
| `settings.gradle.kts` | ◎ | × | × | A 専管 |
| `gradle/libs.versions.toml` | ◎ | △ | △ | 同上 |
| `config/checkstyle/checkstyle.xml` | ◎ | × | × | A 専管 |
| `.github/workflows/*.yml` | ◎ | × | × | A 専管 |
| `CLAUDE.md` / `docs/*.md` | △ | △ | △ | **編集はファイル単位で予約**(Issue) |
| `src/main/java/com/theater/App.java` | ◎ | △ | △ | DI登録の追記は B/C も PR 可。**1Sprintで衝突するなら A が事前に区画(`registerCatalog()`,`registerReservation()`等)を切る** |
| `src/main/java/com/theater/shared/**` | ◎ | × | × | 触りたい変更があれば Issue で A に依頼 |
| `src/main/java/com/theater/identity/**` | ◎ | × | × | |
| `src/main/java/com/theater/catalog/**` | × | ◎ | × | |
| `src/main/java/com/theater/reservation/**` | × | × | ◎ | |
| `src/main/java/com/theater/ordering/**` | × | × | ◎ | |
| `src/main/java/com/theater/ticketing/**` | ◎ | × | × | |
| `src/main/resources/db/migration/V001__*.sql` | ◎ | × | × | A |
| `src/main/resources/db/migration/V010__*.sql` | × | ◎ | × | B |
| `src/main/resources/db/migration/V020__*.sql` | × | × | ◎ | C |
| `src/main/resources/db/migration/V030__*.sql` | × | × | ◎ | C |
| `src/main/resources/db/migration/V040__*.sql` | ◎ | × | × | A |
| `src/main/resources/ui/fxml/login.fxml` | ◎ | × | × | |
| `src/main/resources/ui/fxml/register.fxml` | ◎ | × | × | |
| `src/main/resources/ui/fxml/profile.fxml` | ◎ | × | × | |
| `src/main/resources/ui/fxml/home.fxml` | × | ◎ | × | |
| `src/main/resources/ui/fxml/seat_select.fxml` | × | × | ◎ | |
| `src/main/resources/ui/fxml/checkout.fxml` | × | × | ◎ | |
| `src/main/resources/ui/fxml/tickets.fxml` | ◎ | × | × | |
| `src/main/resources/application.properties` | ◎ | △ | △ | append-only。設定キーは prefix で区画 (`hold.*` は C, `catalog.*` は B) |
| `src/test/resources/db/seed/*.sql` | △ | ◎ | △ | B 主担当。A/C は新キーを追加するだけ |
| `src/testFixtures/java/com/theater/testkit/**` | ◎ | △ | △ | A 専管。追加は PR で A レビュー必須 |

---

## 3. インターフェース合意 (Day-1 で凍結する契約)

> 並列実行のために**最初の1日で合意して凍結**する。これらは Person A が Sprint 0 内で先行実装する。

### 3.1 Domain Repository interface (各BCのdomainパッケージ)

A が雛形PRを切る → B/C が自BCの interface を確定。**シグネチャ確定後は変更時PRレビュー必須**。

```java
// identity/domain/UserRepository.java   (A)
public interface UserRepository {
    Optional<User> findById(UserId id);
    Optional<User> findByEmail(Email email);
    void save(User user);                       // 楽観ロック失敗で OptimisticLockException
}

// catalog/domain/ScreeningRepository.java (B)
public interface ScreeningRepository {
    Optional<Screening> findById(ScreeningId id);
    List<Screening> findUpcoming(Instant from, Instant to);
    void save(Screening screening);
}
// その他も同型

// reservation/domain/SeatStateRepository.java (C)
public interface SeatStateRepository {
    List<SeatState> findByScreening(ScreeningId id);
    /** 影響行数を返す。要求座席数と一致しなければ呼び元が ConflictException を投げる */
    int tryHold(ScreeningId sid, List<SeatId> seats, ReservationId rid, Instant expiresAt, Instant now);
    void release(ReservationId rid);
    void markSold(ReservationId rid, Map<SeatId, TicketId> seatToTicket, Instant now);
}
```

### 3.2 共有カーネル (A 提供)

```java
// shared/tx/UnitOfWork.java
public interface UnitOfWork {
    <R> R execute(Tx mode, Supplier<R> work);   // mode=READ_ONLY | REQUIRED | REQUIRES_NEW
}

// shared/tx/TransactionalUseCase.java   (Template Method)
public abstract class TransactionalUseCase<C, R> {
    protected final UnitOfWork uow;
    protected TransactionalUseCase(UnitOfWork uow) { this.uow = uow; }
    public final R execute(C command) {
        validate(command);
        return uow.execute(txMode(), () -> handle(command));
    }
    protected Tx txMode() { return Tx.REQUIRED; }
    protected void validate(C command) {}
    protected abstract R handle(C command);
}

// shared/eventbus/DomainEventBus.java
public interface DomainEventBus {
    void publish(DomainEvent event);            // Tx内: outboxへ書く / コミット後: 配信
}

// shared/kernel/Clock.java     -- テストで固定可能
public interface Clock { Instant now(); }

// shared/kernel/IdGenerator.java
public interface IdGenerator { String newId(); }   // UUIDv7
```

### 3.3 コンテキスト間の DTO 契約 (Anti-Corruption Layer)

`reservation` ↔ `ordering` ↔ `ticketing` は **互いの domain クラスを直接参照しない**。
代わりに `application.dto.*` の record 型を介す:

```java
// reservation/application/dto/ConfirmedReservationView.java
public record ConfirmedReservationView(
    ReservationId reservationId,
    UserId userId,
    ScreeningId screeningId,
    List<SeatPrice> seats        // record SeatPrice(SeatId id, int priceMinor)
) {}
```

これらの record は **Sprint 0 で A が雛形を切る** → 担当BCが完成させる。

---

## 4. Sprint 計画 (例: 1 Sprint = 1週間 × 3スプリント)

### Sprint 0 (準備, 並列開始前) — 全員参加 / A リード
- [A] Gradle / Spotless / Checkstyle / ArchUnit / GHA をセットアップ
- [A] `shared/**` 完成 (DI / UoW / EventBus / Clock / IdGenerator / Result)
- [A] V001 + Outbox を merge
- [A] `App.java` 骨格と DI 区画 (`SharedModule`,`IdentityModule`,`CatalogModule`,`ReservationModule`,`OrderingModule`,`TicketingModule`) を空でコミット
- [A] `testFixtures/testkit` のベース (`Db.openTestDb()`, `Seeds`, `FixedClock`)
- [B/C] domain interface と DTO record の雛形を A の PR にレビューで貢献 → 凍結

### Sprint 1 (縦串MVP) — 並列
| 担当 | 取組 |
|---|---|
| 🅰 | ID-01 Register / ID-02 Login (UI + UseCase + JdbcUserRepo) |
| 🅱 | CT-01..05 (Query系) + シードSQL作成 + home.fxml |
| 🅲 | RV-01 LoadSeatMap, RV-02 HoldSeats, V020 + 座席UI |

### Sprint 2 (Tx重量) — 並列
| 担当 | 取組 |
|---|---|
| 🅰 | TK-01..03 + V040 + tickets.fxml + (時間あれば profile/logout) |
| 🅱 | CT-11 ScheduleScreening Tx + admin系 (時間内) |
| 🅲 | OR-01..03 Checkout / Cancel + V030 + checkout.fxml |

### Sprint 3 (仕上げ) — 並列
- 全員: 自BCの ACID/同時実行テスト網羅 (`testing.md` 準拠)
- A: ArchUnit Rule 追加 + ドキュメント / Lint修正
- B: 検索性能インデックス + UI仕上げ
- C: ExpireHoldsJob (RV-05) + Outbox 配信ワーカ + 整合性ジョブ

---

## 5. Conflict 回避ルール (運用)

ブランチ戦略は **`main` ← `develop` ← `feat/*`** の3層 (詳細: [`../CONTRIBUTING.md`](../CONTRIBUTING.md))。

1. **ブランチ命名**: `feat/<owner>/<bc>/<topic>` 例: `feat/c/reservation/hold-seats`
   - 修正系は `fix/...`、ドキュメントは `docs/...`、ビルド/CIは `build/...` `ci/...`
   - **base は必ず `develop`** (`main` は release PR でのみ更新)
2. **小さく頻繁にPR**: 1PR < 400行目安。WIP は draft で。
3. **共有ファイル変更は予告**: `build.gradle.kts` / `App.java` / `application.properties` を触る PR は Slack/Issue に「touching shared X」と書く。
4. **migrate ファイルは `V###` 番号衝突を避ける**:
   - 100の位を担当に予約: 0xx=shared, 1xx=catalog, 2xx=reservation, 3xx=ordering, 4xx=ticketing
   - 同担当内では時系列で +1
5. **rebase before push**: `develop` を rebase で取り込んでから push (merge commit を作らない)
6. **ArchUnit Rule** (`testing.md` 参照) で BC 越境参照を **CI で機械検出**。レビュー疲労を避ける。
7. **Daily 5分同期**: 翌日触る共有ファイルだけ口頭で宣言。
8. **PR テンプレ** に「触ったshared file」「変更したinterface」「追加マイグレ番号」を明記する欄を設ける。
9. **`main` は protected**: 直 push 禁止 / force push 禁止 / 必須 CI green / 承認 1 件以上。

---

## 6. 着手順依存グラフ

```
    [A] shared kernel + V001 + interfaces 凍結
          ├─→ [B] catalog (V010, ScreeningRepo)
          │         └─→ [C] reservation が依存 (Screening 必須)
          ├─→ [C] reservation (V020, SeatStateRepo)
          │         └─→ [C] ordering (V030, Checkout)
          │                    └─→ [A] ticketing (V040)
          └─→ [A] identity (V001 拡張、ログイン)
```

> Person A が Sprint 0 で全員のブロッカを外すのが最優先。
> Person C は Sprint 1 で reservation 着手まで Person B の `Screening` テスト用 fakeRepository (testFixtures) を使って先行可能。

---

## 7. 「動作確認」最低ライン (DoD)

各PRで以下が満たされていなければマージ不可:
- [ ] `./gradlew check` が green (test + lint + ArchUnit)
- [ ] 新規ユースケースには **Unit + Repository + Tx** の3層テストがある (`testing.md`)
- [ ] 金銭が絡む UC は `testing.md` の 💰 チェックリスト全項目
- [ ] 並行性が絡む UC は ACID 4要件のテストがある
- [ ] CHANGELOG (or PR description) に「触った shared file / 追加 V### / 変更した interface」を記載

---

## 8. 進捗ログ (実 merge ベース)

> §4 の Sprint 計画は当初案。実際に develop に取り込まれた内容は本節を正本とする。
> PR は `develop` への merge を1単位とする (`develop → main` のリリース PR は本節には載せない)。

| # | Sprint / 段階 | 内容 | Owner | merge 日 | PR |
|---|---|---|---|---|---|
| 0 | bootstrap | Gradle / Spotless / Checkstyle / ArchUnit / GHA / 設計 docs / dir スケルトン | A | 2026-04-30 | (initial commits) |
| 1 | Sprint 0 phase 1 | `shared/{kernel,error,di,tx}` / `V001__shared_identity` / `testkit.{Db,FixedClock,IncrementingIdGenerator}` / `App.bootstrap` (DI 登録 + Flyway migrate) | A | 2026-04-30 | #1 |
| 2 | hotfix | `shared/tx` の READ_ONLY Tx を SQLite 互換 (writable / readOnly 別 DataSource) に修正 | A | 2026-05-04 | #3 |
| 3 | Sprint 1 (B 先行) | catalog: `V010__catalog` / domain (Movie/Screen/Seat/Screening + status/type enum) / `CatalogQueryRepository` + `ScreeningRepository` / CT-01〜05 Query UseCase + DTO / `JdbcCatalogRepository` / `home.fxml` 骨格 | B | 2026-05-03 | (B merge) |
| 4 | docs | `is_published` × `screenings.status` 不変条件の明文化 (UnpublishMovie はスコープ外) | A | 2026-05-04 | #4 |
| 5 | Sprint 0 phase 2 (Q1) | cross-BC ID 4 個 (`MovieId`/`ScreenId`/`ScreeningId`/`SeatId`) を `shared/kernel` へ移動 + `Identifier` interface 新設 | A | 2026-05-04 | #5 |

### 達成済の集約マップ

```
shared/
├── kernel/    ✅ Money, Currency, Result, Clock, IdGenerator, Identifier, MovieId, ScreenId, ScreeningId, SeatId
├── error/     ✅ DomainException 階層 (5 具象)
├── di/        ✅ Container, Module(I/F), Provider, Scope
├── tx/        ✅ UnitOfWork(I/F), JdbcUnitOfWork (writable+readOnly), TransactionalUseCase, Tx
└── eventbus/  ❌ 未着手 (PLAT-01)

catalog/
├── domain/         ✅ 集約 + 2 Repository interface
├── application/    ✅ CT-01〜05 Query UC + 内部 view DTO
├── infrastructure/ ✅ JdbcCatalogRepository (Query + Screening write)
└── ui/             ❌ home.fxml は骨格のみ、Controller 未実装 (CT-06)

identity/    ❌ 未着手   reservation/ ❌ 未着手
ordering/    ❌ 未着手   ticketing/   ❌ 未着手

testkit/  ✅ Db / FixedClock / IncrementingIdGenerator
          ❌ Seeds は inline 状態 (PLAT-02 で関数化予定)
```

---

## 9. 残作業 issue ブレイクダウン (issue 駆動 / 1 issue = 1 PR)

> 各 issue は **GitHub Issue を作成 → 対応 PR で Close** の流れを想定。issue ID は `BC-NN` 形式。番号は単調増加 (sprint 内で着手順とは限らない)。
>
> **"Owner" は推奨担当 / 設計責任者** を示す目安にすぎない。実際の Assignee は手空き状況・専門性・依存関係に応じて他メンバーが pick up しても良い (§1 の Big Picture から逸脱しない範囲で)。
>
> ただし **issue はファイルレベルで衝突しないように分割してある** ので、複数 issue が同時並列で進行しても merge conflict は原則発生しない。詳細は §11。

### 9.1 Sprint 0 finalization (= Q2-Q4 まとめ・直近最優先)

| # | タイトル | Owner | スコープ | Blocked by | Blocks |
|---|---|---|---|---|---|
| **PLAT-01** | `shared/eventbus` 実装 (DomainEvent / DomainEventBus / OutboxDomainEventBus) + Tx 統合テスト | A | `domain_events_outbox` への INSERT が Tx に乗ること、commit/rollback が伝搬すること | (Q1 完了済) | OR-04 |
| **PLAT-02** | 6 つの `*Module.java` 雛形 + `App.bootstrap` の `install` 有効化 + `testkit/Seeds*.java` を BC 別に分割 | A | **Module 側**: SharedModule (DomainEventBus/UnitOfWork bind) / CatalogModule (B の既存リポジトリ bind) / 他 4 BC は空 bind() / `App.bootstrap` で 6 行の `install(...)` を**ここで全て書き切る** (以後 BC issue 側では Module ファイルだけ編集すれば足りるようにする). **Seeds 側**: `testkit/Seeds.java` は aggregator のみ、実体は `IdentitySeeds.java` / `CatalogSeeds.java` / `ReservationSeeds.java` / `OrderingSeeds.java` / `TicketingSeeds.java` の 5 BC 別ファイルに分割。各 BC issue は自 BC の Seeds ファイルだけ編集する設計 (詳細は §11) | (Q1 完了済) | ID-01, CT-06, RV-01, OR-01, TK-01 |
| **PLAT-03** | identity / ticketing の Repository interface 雛形 + `UserId` / `TicketId` を `shared/kernel` 追加 | A | `identity/domain/UserRepository` + `ticketing/domain/TicketRepository` の interface 定義のみ。実装は ID-01 / TK-01 で | (Q1 完了済) | ID-01, TK-01 |
| **PLAT-04** | reservation / ordering の Repository interface draft + `ReservationId` / `OrderId` を `shared/kernel` 追加 | A (draft) → C (合意) | `ReservationRepository` / `SeatStateRepository` / `OrderRepository` / `PaymentRepository` / `RefundRepository` の interface 雛形 | (Q1 完了済) | RV-01, OR-01 |
| **PLAT-05** | cross-BC DTO 雛形 (`application/dto/*`) | A | `reservation/application/dto/ConfirmedReservationView` (OR-04 が消費) など最小セット | PLAT-04 | OR-04 |

### 9.2 Sprint 1 (縦串 MVP)

#### identity (A)

| # | タイトル | Owner | スコープ | Blocked by | Blocks |
|---|---|---|---|---|---|
| **ID-01** | identity domain (User/Email/PasswordHash) + `JdbcUserRepository` 実装 | A | bcrypt ハッシュ / email UNIQUE / Repository テスト (Repository IT) | PLAT-02, PLAT-03 | ID-02, ID-03 |
| **ID-02** | RegisterUser UseCase + テスト | A | Tx Cmd / email UNIQUE 違反テスト / Atomicity / Repository / Unit | ID-01 | ID-04 |
| **ID-03** | Login UseCase + Session (`CurrentUserHolder` Singleton) + テスト | A | password 検証 / 失敗時例外 / Singleton セッション保持 | ID-01 | ID-04, OR-04 |
| **ID-04** | login.fxml / register.fxml Controller + UseCase wire (UI 動作確認) | A | JavaFX Controller / ViewModel / 入力バリデーション / 画面遷移 | ID-02, ID-03 | (UI 統合) |

#### catalog (B / 残)

| # | タイトル | Owner | スコープ | Blocked by | Blocks |
|---|---|---|---|---|---|
| **CT-06** | home.fxml Controller + Catalog UseCase wire (UI 動作確認) | B | 一覧/検索/詳細表示 + 「座席を選ぶ」ボタンから seat_select 画面遷移 (RV-05 完了後) | PLAT-02 | (UI 統合) |
| **CT-07** (任意) | (admin) PublishMovie Cmd UC | B | 課題スコープでは seed SQL 代替で十分。実装するなら Tx | PLAT-02 | — |

#### reservation (C)

| # | タイトル | Owner | スコープ | Blocked by | Blocks |
|---|---|---|---|---|---|
| **RV-01** | `V020__reservation` + reservation domain + `JdbcSeatStateRepository` / `JdbcReservationRepository` | C | reservations + seat_states テーブル + 多段 CHECK 制約 + 集約 + Repository IT (CHECK / FK / UNIQUE 検証) | PLAT-02, PLAT-04 | RV-02, RV-03, RV-04, OR-01 |
| **RV-02** | RV-01 LoadSeatMap (Query) + RV-02 HoldSeats (`BEGIN IMMEDIATE` + 多層防御) + ACID 4 要件テスト + 同時実行テスト | C | `UPDATE seat_states WHERE status='AVAILABLE'` の影響行数判定 / 100 並列での衝突解消 / Atomicity / Consistency / Isolation / Durability 全部 | RV-01 | RV-03, RV-05, OR-04 |
| **RV-03** | RV-03 ReleaseHold + テスト | C | 自分の予約のみ解放可 / 状態遷移検証 | RV-02 | (UI) |
| **RV-04** | RV-05 ExpireHolds Job + テスト | C | 期限切れ HOLD を EXPIRED に / seat_states を AVAILABLE に / **冪等** | RV-02 | (運用) |
| **RV-05** | seat_select.fxml Controller + UseCase wire (UI 動作確認) | C | 座席グリッド描画 + クリックで HOLD | RV-02 | (UI 統合) |

#### ordering (C)

| # | タイトル | Owner | スコープ | Blocked by | Blocks |
|---|---|---|---|---|---|
| **OR-01** | `V030__ordering` + ordering domain (Order/Payment/Refund) + Repository 実装 | C | orders + payments + refunds テーブル + UNIQUE 制約 (1予約1注文 / 1注文1返金) + 集約 + Repository IT | PLAT-02, PLAT-04, RV-01 | OR-02, OR-04 |
| **OR-02** | `MockPaymentGateway` (テスト用 PaymentGateway 実装) | C | success_rate / failNthCall / delayMs (concurrency シミュ用) | OR-01 | OR-04 |
| **OR-03** | OR-01 StartCheckout (Query) | C | HOLD 中 Reservation → 合計金額再計算 → 表示 DTO (💰 Money 型) | OR-01, ID-03 | OR-06 |
| **OR-04** ★ | OR-02 Checkout (重量 Tx 12-step) + ACID 4要件 + 💰 チェックリスト全項目 | C | **本案件最重量**。docs/data_model.md §4 のシーケンスを忠実に。Atomicity (全 12 ステップが all-or-nothing) / Consistency (`screenings` 集計値 = `seat_states` 集計) / Isolation (Lost Update / Phantom 排除) / Durability / 二重課金不可 / 二重返金不可 | RV-02, OR-01, OR-02, TK-01, ID-03, **PLAT-01**, **PLAT-05** | OR-05, OR-06, TK-02 |
| **OR-05** | OR-03 CancelOrder + Refund (Mock) + テスト | C | 上映前のみ / Refund 冪等 (UNIQUE で守る) | OR-04 | (Sprint 2) |
| **OR-06** | checkout.fxml Controller + UseCase wire (UI 動作確認) | C | 金額確認 → 決済ボタン → 成功/失敗ハンドリング | OR-03, OR-04 | (UI 統合) |

#### ticketing (A)

| # | タイトル | Owner | スコープ | Blocked by | Blocks |
|---|---|---|---|---|---|
| **TK-01** | `V040__ticketing` + Ticket domain + `JdbcTicketRepository` | A | tickets テーブル + `uq_tickets_active_seat` パーシャル UNIQUE INDEX (★ ダブルブッキング最終防壁) + Repository IT | PLAT-02, PLAT-03 | OR-04, TK-02 |
| **TK-02** | TK-01 ListMyTickets + TK-02 GetTicketDetail (Query UC) | A | 自分のチケット一覧 (ACTIVE/USED/CANCELED) / 詳細 + QR (base64 文字列) | TK-01, OR-04 (発券されないと表示するチケットがない) | TK-03 |
| **TK-03** | tickets.fxml Controller + UseCase wire (UI 動作確認) | A | 一覧 + 詳細パネル | TK-02 | (UI 統合) |

### 9.3 Sprint 2 (補強 / 統合)

| # | タイトル | Owner | スコープ | Blocked by |
|---|---|---|---|---|
| **TK-04** | TK-03 MarkUsed (Cmd) + テスト | A | ACTIVE→USED 状態遷移 / 上映時間内のみ / 冪等 | TK-02 |
| **OR-07** | OR-04 RefundOrder (admin/連鎖) + 冪等テスト | C | refunds.order_id UNIQUE で二重防止 / CT-12 CancelScreening の連鎖呼出と整合 | OR-05 |
| **PLAT-06** | OutboxPublisher Job + テスト | A | 1 秒間隔で `domain_events_outbox` 未配信を取り出し配信 (本案件では log 出力で OK) / `published_at` 更新の冪等 | PLAT-01 |
| **TEST-01** | E2E 通しシナリオテスト | 全員 | docs/spec.md §5 の MVP DoD 9 項目を1テストで通す | OR-04, ID-04, RV-05, TK-03 |
| **TEST-02** | ArchUnit ルール強化 | A | `withOptionalLayers(true)` 解除 / BC 越境参照 ArchRule 追加 / `domain` の package-private 強制 | (Sprint 1 ほぼ完了) |

### 9.4 Sprint 3 (リリース)

| # | タイトル | Owner | スコープ |
|---|---|---|---|
| **REL-01** | docs ポリッシュ | A | CLAUDE.md / spec.md / 本書 (§8 進捗ログ) を最終状態に反映 |
| **REL-02** | develop → main release PR | A | Branch protection 必須 CI 全 green / 1+ approval |
| **REL-03** | レポート提出資料 | 全員 | 採用 GoF 一覧 / Java 17 機能 / テスト戦略 (`docs/architecture.md` §5/§6 と `docs/testing.md` から引用) |

---

## 10. PR 依存グラフ (Sprint 0 finalization 以降)

```
                              [完了]
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│  Sprint 0 finalization (A 専担)                                    │
│                                                                    │
│  PLAT-01 (eventbus) ──┐                                            │
│  PLAT-02 (Modules)  ──┤                                            │
│  PLAT-03 (A repos)  ──┼──────► [全員 unblock]                      │
│  PLAT-04 (C repos)  ──┤                                            │
│  PLAT-05 (DTO)      ──┘                                            │
└──────────────────────────────────────────────────────────────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        ▼                       ▼                       ▼
   ┌─────────┐             ┌─────────┐             ┌─────────────┐
   │ A: identity│           │ B: catalog残 │         │ C: reservation │
   └─────────┘             └─────────┘             └─────────────┘
   ID-01 → ID-02 ─┐         CT-06              RV-01 → RV-02 → RV-03/04/05
                  ├→ ID-04                                │
   ID-01 → ID-03 ─┘                                       │
                                                          ▼
                                              ┌──────────────────┐
                                              │ C: ordering        │
                                              └──────────────────┘
                                              OR-01 → OR-02 ─┐
                                              OR-01 → OR-03 ─┤
                                                             ▼
                                                      OR-04 (★ 最重量 Tx)
                                                             ▲
                                              ┌──────────────────┐
                                              │ A: ticketing       │
                                              └──────────────────┘
                                              TK-01 → TK-02 ─→ TK-03
                                                       │
                                                       └──→ (OR-04 へ)

                  Sprint 2:  TK-04, OR-05, OR-07, PLAT-06, TEST-01, TEST-02
                  Sprint 3:  REL-01..03
```

### Critical path (= MVP DoD に必須の最短経路)

```
PLAT-{01..05}  ──►  RV-01  ──►  RV-02  ──►  OR-04  ──►  TEST-01  ──►  REL-02
                                  ▲             ▲
                                  │             │
                ID-01 ─► ID-03 ───┘             │
                                                │
                TK-01 ──────────────────────────┘
```

OR-04 (Checkout) が**5つの BC を横断**するため、ここに到達する前に identity (Login)・reservation (HoldSeats)・ordering (Order/Payment) ・ticketing (Ticket) と shared (DomainEventBus / DTO) が揃っている必要がある。Sprint 2 開始時点で OR-04 が green なら MVP は実質完成。

### 並列化のしどころ

- Sprint 0 finalization (PLAT-01..05) 中に B は **CT-06** を着手可能 (PLAT-02 の `CatalogModule` 雛形があれば)
- Sprint 1 で A の **identity 系列 (ID-01..04)** と C の **reservation 系列 (RV-01..05)** は完全並列
- C の **OR-01 / OR-02 / OR-03** は RV-02 完了前から着手可能 (interface ベースで開発、結線は OR-04 で)
- A の **TK-01** は OR-04 着手前に完了させ、OR-04 の Tx 実装で `JdbcTicketRepository.insert` を呼べるようにしておく

### Conflict 警戒ポイント

| ファイル | 触る issue | 調停案 |
|---|---|---|
| `App.java` (`bootstrap`) | PLAT-02, ID-04, CT-06, RV-05, OR-06, TK-03 | PLAT-02 で全 BC のスケルトン install を完成させる → 各 UI issue では既存 install を呼ぶだけにする |
| `application.properties` | PLAT-02, RV-04 (`reservation.expireHolds.intervalMs`), PLAT-06 (`shared.outbox.intervalMs`) | 既に prefix で区画済 (`task_split.md §2`)、append-only |
| `db/migration/V###` | RV-01 (V020), OR-01 (V030), TK-01 (V040) | 番号区画は決定済、競合しない |
| `home.fxml` `seat_select.fxml` 等 | 各 UI issue | 1 fxml = 1 owner なので競合なし |

---

## 11. ファイルレベル衝突回避設計

issue を「同じファイルを2人が同時に編集する」状態にしないための具体設計。Owner はあくまで推奨担当 (§9) で、現場では手空きの人が pick up するため、issue 同士のファイル独立性が運用の生命線になる。

### 11.1 共有ファイルの appendable 化

#### `App.bootstrap()` の install 行

**PLAT-02 で 6 つの install 行をすべて書き切る** ことが本設計の鍵。空の Module がそこに紐づく形で先に commit してしまうので、以後の BC issue は **Module ファイルの中身を編集するだけ** で済み、`App.java` を再度開く必要がない。

```java
// PLAT-02 で書き切る形 (BC issue では触らない)
container.install(new SharedModule());        // PLAT-02 で実装、PLAT-01 が DomainEventBus 追加で再編集
container.install(new IdentityModule());      // ID-01 で中身を実装
container.install(new CatalogModule());       // PLAT-02 で B の既存リポジトリを bind 済
container.install(new ReservationModule());   // RV-01 で中身を実装
container.install(new OrderingModule());      // OR-01 で中身を実装
container.install(new TicketingModule());     // TK-01 で中身を実装
```

#### `testkit/Seeds*.java` の BC 別分割

**Seeds は単一クラスにせず、BC ごとに別クラスへ分割**。aggregator (`Seeds.java`) は PLAT-02 が一度書いたら以後変更しない:

```
src/testFixtures/java/com/theater/testkit/
├── Seeds.java                ← aggregator (PLAT-02 が作成、以後 immutable)
├── IdentitySeeds.java        ← ID-01 が編集 (user(...) など)
├── CatalogSeeds.java         ← B が編集 (movie/screen/screening/seat seed)
├── ReservationSeeds.java     ← RV-01 が編集 (reservation/seatState seed)
├── OrderingSeeds.java        ← OR-01 が編集 (order/payment seed)
└── TicketingSeeds.java       ← TK-01 が編集 (ticket seed)
```

aggregator の例:
```java
public final class Seeds {
  public final IdentitySeeds identity;
  public final CatalogSeeds catalog;
  public final ReservationSeeds reservation;
  public final OrderingSeeds ordering;
  public final TicketingSeeds ticketing;

  public Seeds(UnitOfWork uow, Clock clock, IdGenerator ids) {
    this.identity = new IdentitySeeds(uow, clock, ids);
    this.catalog = new CatalogSeeds(uow, clock, ids);
    /* ... */
  }
}
```

テストからは `seeds.reservation.holdSeats(...)` のようにナビゲートする。

#### `application.properties`

prefix で区画済 (§2)。各 BC issue は自 prefix のキーを **append のみ**。値の変更が必要な場合は PR description に明記して同 PR 内で完結させる。

#### `*Module.java` (各 BC 1 ファイル)

`infrastructure/<BC>Module.java` は BC ごとに分かれているので、複数 issue が同 BC の Module を編集する場合のみ衝突しうる。同 BC 内の issue 順序は依存グラフ (§10) で時系列が決まっているため、現実には同時編集にならない。

### 11.2 issue × 触るファイル マトリクス

| Issue | 新規作成 | 編集 (BC 内 / 競合なし) | 編集 (共有 / 順序依存) |
|---|---|---|---|
| **PLAT-01** | `shared/eventbus/{DomainEvent, DomainEventBus, OutboxDomainEventBus}.java` + テスト | — | `shared/SharedModule.java` (`DomainEventBus` bind 追加) |
| **PLAT-02** | 6 `*Module.java` / 5 `*Seeds.java` / `Seeds.java` aggregator / `application.properties` キー | — | `App.java` (install 6 行) |
| **PLAT-03** | `shared/kernel/UserId.java` / `shared/kernel/TicketId.java` / `identity/domain/UserRepository.java` / `ticketing/domain/TicketRepository.java` | — | — |
| **PLAT-04** | `shared/kernel/ReservationId.java` / `shared/kernel/OrderId.java` / `reservation/domain/*Repository.java` / `ordering/domain/*Repository.java` | — | — |
| **PLAT-05** | `reservation/application/dto/*` / `ordering/application/dto/*` | — | — |
| **ID-01** | `identity/domain/{User, Email, PasswordHash}.java` / `identity/infrastructure/JdbcUserRepository.java` + Repository IT | `testkit/IdentitySeeds.java` / `identity/infrastructure/IdentityModule.java` | — |
| **ID-02** | `identity/application/RegisterUserUseCase.java` + テスト | — | — |
| **ID-03** | `identity/application/LoginUseCase.java` / `identity/.../CurrentUserHolder.java` + テスト | `identity/infrastructure/IdentityModule.java` | — |
| **ID-04** | `identity/ui/{LoginController, RegisterController}.java` | `login.fxml` / `register.fxml` | — |
| **CT-06** | `catalog/ui/HomeController.java` 等 | `home.fxml` | — |
| **RV-01** | `V020__reservation.sql` / `reservation/domain/*` / `reservation/infrastructure/*Repository.java` + Repository IT | `testkit/ReservationSeeds.java` / `reservation/infrastructure/ReservationModule.java` | — |
| **RV-02** | `reservation/application/{LoadSeatMap, HoldSeats}UseCase.java` + ACID/Concurrency テスト | — | — |
| **RV-03** | `reservation/application/ReleaseHoldUseCase.java` + テスト | — | — |
| **RV-04** | `reservation/application/ExpireHoldsJob.java` + テスト | — | — |
| **RV-05** | `reservation/ui/SeatSelectController.java` | `seat_select.fxml` | — |
| **OR-01** | `V030__ordering.sql` / `ordering/domain/{Order, Payment, Refund}.java` / `ordering/infrastructure/*Repository.java` + Repository IT | `testkit/OrderingSeeds.java` / `ordering/infrastructure/OrderingModule.java` | — |
| **OR-02** | `ordering/infrastructure/MockPaymentGateway.java` + テスト | `ordering/infrastructure/OrderingModule.java` (PaymentGateway bind) | — |
| **OR-03** | `ordering/application/StartCheckoutUseCase.java` + テスト | — | — |
| **OR-04** | `ordering/application/CheckoutUseCase.java` + ACID 4要件 + 💰 全項目テスト | — | — |
| **OR-05** | `ordering/application/CancelOrderUseCase.java` + テスト | — | — |
| **OR-06** | `ordering/ui/CheckoutController.java` | `checkout.fxml` | — |
| **TK-01** | `V040__ticketing.sql` / `ticketing/domain/Ticket.java` / `ticketing/infrastructure/JdbcTicketRepository.java` + Repository IT | `testkit/TicketingSeeds.java` / `ticketing/infrastructure/TicketingModule.java` | — |
| **TK-02** | `ticketing/application/{ListMyTickets, GetTicketDetail}UseCase.java` + テスト | — | — |
| **TK-03** | `ticketing/ui/TicketsController.java` | `tickets.fxml` | — |

### 11.3 衝突しうる残り 2 箇所と運用ルール

| 共有ファイル | 触る issue (順序依存) | 運用 |
|---|---|---|
| **`shared/SharedModule.java`** | PLAT-02 (作成) → PLAT-01 (`DomainEventBus` bind 追加) → PLAT-06 (`OutboxPublisher` 起動) | 全部 A 担当・順序決まり済。3 PR 連続で1人が処理するなら無問題 |
| **`App.java`** | PLAT-02 で書き切る → 以後 PLAT-01 が DomainEventBus を bind するため `SharedModule` を編集するが、`App.java` は触らない | PLAT-02 完了後は touch しない契約 |

### 11.4 これで何が保証されるか

- **A と C の完全並列**: PLAT-02 完了後、A の identity / ticketing 系列 (ID-01..04, TK-01..03) と C の reservation / ordering 系列 (RV-01..05, OR-01..06) は **どの 1 ファイルも共有しない**
- **B が手空きで pick up しやすい**: 例えば B が RV-04 (ExpireHolds Job) を取っても、編集対象は `reservation/application/ExpireHoldsJob.java` + テストのみで、C の他 issue とぶつからない
- **issue assignee の付け替え自由度**: Owner = A の issue を C が取っても、C の他 issue と同じ BC のファイルを触る場合に限って (例: C が ID-01 を取ったら identity/domain と ticketing/domain は重ならないので OK)、ファイル衝突は発生しない

### 11.5 ArchUnit との関係

`docs/testing.md §6` の ArchUnit ルールはレイヤ依存違反を機械検出するが、issue 間のファイル衝突は別の関心事。本節の設計が機能していれば issue 単位の衝突は **PR review 不要レベル** で起きないはず。万一起きたら本節の "issue × ファイル" マトリクスを見直すサインとして扱う。
