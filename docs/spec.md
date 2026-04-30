# 仕様書 (Master Spec) — 映画館チケット予約システム

> 本ファイルは「**何を作るか**」を一望するマスタ。
> 詳細は配下ドキュメントへのリンクを辿る。設計判断や差分はそれぞれの専門ドキュメント側に書き、本書は **what** に集中する。

---

## 0. 1行サマリ

JavaFX デスクトップ上で動く映画館のチケット予約・購入システム。複数ユーザが同一座席を同時に取り合うシナリオで**ダブルブッキングが発生しない**こと、決済を含む取引が **ACID** で完結することを設計上の最重要要件とする。

---

## 1. 背景・目的

| 項目 | 内容 |
|---|---|
| 性質 | 学校課題 (チーム3人) |
| デプロイ | しない (ローカル動作のみ) |
| 学習目的 | ① ログイン/予約/同時実行など実務的トピックの体験 / ② DDD (戦術設計) の実践 / ③ GoF パターンの意識的活用 / ④ Java 17 の新機能活用 |
| 提出物 (`docs/todo.md`) | プログラム本文 / SQLテーブル定義 / テストコード / 解説レポート |

---

## 2. 技術スタック (確定)

| 領域 | 採用 |
|---|---|
| 言語 | **Java 17** (Temurin) |
| UI | **JavaFX 21** (FXML + Controller, MVVM) |
| データ保存 | **SQLite 3.45+** (xerial/sqlite-jdbc, WAL モード) |
| マイグレーション | Flyway (組込) |
| ビルド | Gradle 8 (Kotlin DSL) |
| アーキテクチャ | DDD 戦術設計 + 自作 DI / 自作 UnitOfWork (GoF パターン顕在化) |
| テスト | JUnit 5 + AssertJ + Mockito + ArchUnit + Awaitility |
| Lint/Format | Spotless (google-java-format) + Checkstyle + ErrorProne + ArchUnit |
| CI | GitHub Actions |

詳細は `architecture.md` §1 / `ci_lint.md`。

---

## 3. 機能スコープ — 全体像

```
┌──────────────────────────────────────────────────────────────┐
│                    映画館チケット予約システム                  │
├────────────┬────────────┬────────────┬────────────┬────────────┤
│  認証画面   │  ホーム画面  │  予約画面    │ チェックアウト │ チケット画面 │
│  (identity) │  (catalog)   │ (reservation)│  (ordering)   │ (ticketing) │
├────────────┴────────────┴────────────┴────────────┴────────────┤
│        shared kernel (DI, UnitOfWork, EventBus, Clock, Id)     │
├──────────────────────────────────────────────────────────────┤
│                      SQLite (WAL, ACID)                        │
└──────────────────────────────────────────────────────────────┘
```

5 つの **Bounded Context** で構成。BC ごとの責務は `architecture.md` §3 / 各UCは `features.md`。

---

## 4. 画面 / 機能カタログ (マスタ)

> `[ID]` は `features.md` のユースケースID。`🔒` ACID必須 / `💰` 金銭整合必須。
> 「優先度」は MVP 完成に必要かどうか: ★必須 / ☆任意 (時間あれば)。

### 4.1 認証画面 (identity)

| 優先 | UC | 機能 |
|:-:|---|---|
| ★ | ID-01 | RegisterUser — メール+PWでユーザー登録 |
| ★ | ID-02 | Login — ログイン (Session発行) |
| ★ | ID-03 | Logout — Session破棄 |
| ☆ | ID-04 | GetMyProfile — マイプロフィール取得 |
| ☆ | ID-05 | UpdateProfile — プロフィール変更 |
| ☆ | ID-06 | ChangePassword — パスワード変更 |

**画面**: `login.fxml` / `register.fxml` / `profile.fxml`

### 4.2 ホーム画面 (catalog)

| 優先 | UC | 機能 |
|:-:|---|---|
| ★ | CT-01 | ListPublishedMovies — 公開中映画一覧 |
| ★ | CT-04 | ListUpcomingScreenings — 直近1週間の上映会 |
| ★ | CT-05 | GetScreeningDetail — 上映会詳細 (席数集計込み) |
| ★ | CT-03 | GetMovieDetail — 映画詳細 + 上映予定 |
| ☆ | CT-02 | SearchMovies — タイトル検索 |
| ☆ | CT-06〜CT-10 | (admin) 映画/部屋/座席のマスタ管理 — シードSQLで代替可 |
| ☆ | CT-11 🔒 | (admin) ScheduleScreening — 上映会作成 + seat_states 一括INSERT |
| ☆ | CT-12 🔒💰 | (admin) CancelScreening — 既存予約/注文の連鎖返金 |

**画面**: `home.fxml` (一覧/検索/詳細パネル)

### 4.3 予約画面 (reservation) — 座席選択

| 優先 | UC | 機能 |
|:-:|---|---|
| ★ | RV-01 | LoadSeatMap — 座席マップ表示 |
| ★ | RV-02 🔒 | **HoldSeats — 座席を HOLD する (期限10分)** ★中核 |
| ★ | RV-03 🔒 | ReleaseHold — HOLD解放 |
| ★ | RV-05 🔒 | ExpireHolds — 期限切れHOLDの自動戻し (バックグラウンド/1秒間隔) |
| ☆ | RV-04 🔒 | ExtendHold — 期限延長 |
| ★ | RV-06 | GetMyActiveReservation — 自分の HOLD 中予約取得 |

**画面**: `seat_select.fxml` (座席グリッド + 選択状態)
**最重要不変条件**: 同一上映会の同一座席を **同時に複数ユーザに HOLD/SOLD させない**。

### 4.4 チェックアウト (ordering)

| 優先 | UC | 機能 |
|:-:|---|---|
| ★ | OR-01 💰 | StartCheckout — 合計金額/座席詳細表示 (再計算) |
| ★ | OR-02 🔒💰 | **Checkout — 注文確定 / 決済 / チケット発行を1Tx** ★最重量 |
| ★ | OR-03 🔒💰 | CancelOrder — 上映前の注文取消 + 返金 |
| ☆ | OR-04 🔒💰 | RefundOrder — (連鎖返金用、冪等) |
| ★ | OR-05 | GetMyOrders — 注文履歴 |

**画面**: `checkout.fxml`
**Payment**: `MockPaymentGateway` で外部決済を模擬 (`success_rate` で失敗テスト)。

### 4.5 チケット画面 (ticketing)

| 優先 | UC | 機能 |
|:-:|---|---|
| ★ | TK-01 | ListMyTickets — 保有チケット一覧 |
| ★ | TK-02 | GetTicketDetail — チケット詳細 |
| ☆ | TK-03 🔒 | MarkUsed — 利用済みマーク (もぎり相当) |
| ☆ | TK-04 🔒💰 | (admin) RevokeTicket — チケット強制取消 |

**画面**: `tickets.fxml`

### 4.6 横断 (shared)

| 機能 | 採用パターン |
|---|---|
| DI Container | Singleton + Registry |
| UnitOfWork / TxManager | Template Method + Command |
| DomainEventBus + Outbox | Observer + Outbox |
| Clock (テスト固定可) | Strategy |
| IdGenerator (UUIDv7) | Strategy |
| CurrentUserHolder (Session) | Singleton |

---

## 5. MVP 完成条件 (Definition of Done)

以下の通しシナリオが全て動くこと:

1. **新規登録 → ログイン** (ID-01 → ID-02)
2. **ホームで上映会一覧表示 → 上映会詳細を開く** (CT-04 → CT-05)
3. **座席を3つ選んで HOLD する** (RV-02)
4. **チェックアウト画面で合計金額確認** (OR-01)
5. **決済 (Mock成功) → 注文確定 → チケット発行** (OR-02)
6. **チケット画面で発券されたチケットを確認** (TK-01 → TK-02)
7. **HOLD したまま放置 → 10分後に解放されている** (RV-05)
8. **同時に同じ座席を取り合うとダブルブッキングしない** (Concurrencyテストで保証)
9. **決済失敗時に注文/チケット/座席状態のいずれも変化していない** (Atomicityテストで保証)

---

## 6. 非機能要件 (NFR)

| カテゴリ | 要件 |
|---|---|
| **正確性** | ダブルブッキング絶対不可 (4層防御: 集約不変条件 / Tx境界 / DB CHECK / DB UNIQUE) |
| **整合性** | 金銭の二重課金/二重返金不可 (DB UNIQUE + テスト) |
| **同時実行** | HOLD は同一座席を 2 並列で取り合うと厳密に1つだけ成功 |
| **永続性** | COMMIT 後は再起動しても残る (WAL リカバリ) |
| **性能** | 単一プロセス想定。100座席規模で UI 操作 < 100ms |
| **可搬性** | リポジトリを clone して `./gradlew run` だけで起動 |
| **品質** | 行カバレッジ 全体70% / `reservation` `ordering` 85% / Lint warnings 0 |
| **アーキ違反検出** | ArchUnit が CI で実行され、レイヤ/BC 違反は merge 不可 |

詳細は `testing.md`。

---

## 7. 課題要件 (`docs/todo.md`) とのマッピング

| 要件 | 対応 |
|---|---|
| データベースを使うこと | **SQLite** + Flyway マイグレーション (`data_model.md`) |
| JUnit によるテスト | JUnit 5 + ACID/Concurrency/Repository/Unit の4階層 (`testing.md`) |
| GoF デザインパターン1つ以上 | **10種** を意図配置 (Singleton/Factory/Repository/UnitOfWork/Command/Template Method/Strategy/Observer/State/Decorator)。`architecture.md` §5 |
| Java 17 新機能 | record / sealed / pattern matching switch / text block を VO・状態・SQL で使用。`architecture.md` §6 |
| 提出: プログラム本文 | `src/main/**` |
| 提出: SQL | `src/main/resources/db/migration/V###__*.sql` |
| 提出: テストコード | `src/test/**` + `src/testFixtures/**` |
| 提出: レポート | 別途。`architecture.md` §5/§6 を引用すれば書きやすい |

---

## 8. アクター / ロール

| ロール | 権限 |
|---|---|
| `USER` | 登録/ログイン/上映閲覧/予約/購入/自チケット閲覧 |
| `ADMIN` | (任意機能) 映画・部屋・座席・上映会のマスタ管理、Screening取消、チケット強制取消 |

`User.role` 列で表現。本案件のスコープでは ADMIN UI は最低限 or シード SQL で代替可。

---

## 9. スコープ外 (今回作らないもの)

- 実決済連携 (Stripe等) — Mock のみ
- メール通知 / SMS / PUSH
- 多言語対応 (UI は日本語のみ)
- 複数会場 (1会場・複数 screen は対応)
- 座席ランクごとの動的料金 (定価のみ)
- 払戻ポリシ (キャンセル手数料)
- リアルタイム他端末同期 UI (シングルプロセス前提)
- モバイル / Web

---

## 10. ドキュメントマップ

| ファイル | 役割 |
|---|---|
| `spec.md` (本書) | **what** を一望するマスタ |
| [`architecture.md`](architecture.md) | DDD レイヤ / BC / dir構造 / 採用GoF / 起動シーケンス |
| [`features.md`](features.md) | 全UC (ID付き) と画面・契約 |
| [`data_model.md`](data_model.md) | SQLite スキーマ正本 / 不変条件 / SQLパターン |
| [`task_split.md`](task_split.md) | 3人分担とファイル所有権マップ |
| [`testing.md`](testing.md) | **ACID/同時実行/金銭整合のJUnit規約** (Claude 指示書) |
| [`ci_lint.md`](ci_lint.md) | Gradle / Spotless / Checkstyle / ArchUnit / GHA |
| [`data_structure.md`](data_structure.md) | 旧Firestore案 (legacy reference; 正本は data_model.md) |
| [`todo.md`](todo.md) | 課題要件原文 |
| `../CLAUDE.md` | Claude Code への作業指示 (リポジトリ直下) |

---

## 11. 主要用語 (Glossary)

| 用語 | 意味 |
|---|---|
| Movie (映画) | 上映する作品 |
| Screen (部屋) | 上映する物理的な部屋 (スクリーン) |
| Seat (座席) | 部屋に紐づく物理座席 (`A-10` 等) |
| Screening (上映会) | 「いつ・どの映画を・どの部屋で」上映するかの1イベント |
| SeatState (座席状態) | 上映会×座席ごとの予約状態 (AVAILABLE/HOLD/SOLD/BLOCKED) |
| Reservation (予約) | ユーザが特定の上映会に対して **HOLD** している状態 (期限つき) |
| Order (注文) | Reservation を確定して支払う商行為 |
| Payment (決済) | Order に対する支払い (PENDING/SUCCESS/FAILED/REFUNDED) |
| Refund (返金) | Order に対する返金 (1注文1返金、UNIQUE 制約) |
| Ticket (チケット) | 確定後に座席1席ごとに発行される入場券 |
| HOLD | Reservation 期間中の座席押さえ (期限切れで自動解放) |
| BC | Bounded Context (DDD) |
| Tx | データベーストランザクション |
