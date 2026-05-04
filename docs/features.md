# 機能棚卸 (Feature Inventory)

> `spec.md` で挙がった画面に対応する**ユースケース**を、Bounded Context ごとに完全列挙する。
> 各ユースケースは `application` レイヤの `*UseCase` クラスに 1:1 対応する。
> Conflict回避とテスト指針 (`testing.md`) は本リスト ID を参照する。

---

## 0. 凡例

- 🅰=Person A / 🅱=Person B / 🅲=Person C
- `[Cmd]` = Command (書込/状態変更, トランザクション要)
- `[Qry]` = Query (読込のみ, Tx不要 or READ-ONLY)
- 🔒 = ACID/Tx の検証必須 (`testing.md` の対象)
- 💰 = 金銭整合 (Money/価格計算/返金)

---

## 1. identity (認証画面) 🅰

| ID | UC名 | 種別 | 概要 | 重要不変条件 |
|---|---|---|---|---|
| ID-01 | RegisterUser | Cmd | メール+PWでユーザー登録 | email UNIQUE / PWはbcryptハッシュ |
| ID-02 | Login | Cmd | メール+PWで認証しSessionを発行 | 連続失敗5回でlock(任意) |
| ID-03 | Logout | Cmd | Session破棄 | |
| ID-04 | GetMyProfile | Qry | 現在ユーザーの情報取得 | |
| ID-05 | UpdateProfile | Cmd | 表示名/メール変更 (旧PWで再認証) | email UNIQUE |
| ID-06 | ChangePassword | Cmd | 現PW検証→新PW保存 | |

**画面**: `login.fxml`, `register.fxml`, `profile.fxml`
**Session**: メモリ常駐の `CurrentUser` (Singleton)。アプリ終了で破棄。永続化なし。

---

## 2. catalog (ホーム画面) 🅱

| ID | UC名 | 種別 | 概要 | 不変条件 |
|---|---|---|---|---|
| CT-01 | ListPublishedMovies | Qry | 公開中の映画一覧 | `is_published=1` のみ |
| CT-02 | SearchMovies | Qry | タイトル部分一致検索 | |
| CT-03 | GetMovieDetail | Qry | 映画詳細 + 上映予定 | |
| CT-04 | ListUpcomingScreenings | Qry | 直近1週間の上映会一覧 | `status=OPEN` and `now < sales_end_at` |
| CT-05 | GetScreeningDetail | Qry | 上映会1件 + 集計 | available/reserved/sold の集計値 |
| CT-06 | (admin) CreateMovie | Cmd | | duration_minutes>0 |
| CT-07 | (admin) UpdateMovie | Cmd | | |
| CT-08 | (admin) PublishMovie | Cmd | 公開フラグON | |
| CT-09 | (admin) DefineScreen | Cmd | 部屋(screen)定義 | total_seats>0 |
| CT-10 | (admin) DefineSeats | Cmd | 部屋に座席を一括登録 | (screen,row,number) UNIQUE |
| CT-11 | (admin) ScheduleScreening | Cmd 🔒 | 上映会を作成 + `seat_states` を AVAILABLE で全件INSERT | start<end / sales<=start / 同一screenの時間重複なし |
| CT-12 | (admin) CancelScreening | Cmd 🔒💰 | 上映会キャンセル → 既存予約/注文を全返金フロー起動 | Refund連鎖 |

**画面**: `home.fxml` (一覧/検索/詳細), `admin_*.fxml` (任意・余裕があれば)

> 課題スコープでは admin 系 (CT-06〜CT-12) は **シードSQL** で代替してもよい。
> ただし CT-11 / CT-12 はTxの良い題材なのでせめて test だけは書く。

### `movies.is_published` と `screenings.status` の関係

2つのフラグは粒度が違う直交した関心事:

- `movies.is_published` (boolean / 映画単位): **customer-visibility のゲート** — 一覧 / 検索 / 映画ページに出すかどうか
- `screenings.status` (enum / 上映会単位): **販売サイクルのゲート** — `SCHEDULED` (枠だけ作成済) / `OPEN` (販売中) / `CLOSED` (販売終了 or 上映後) / `CANCELED` (中止 + 返金済)

不変条件 (定常状態として):

> **`is_published=1` の映画にのみ `status=OPEN` の screening が存在しうる。**

この不変条件は **書込 UseCase 側で守る** (DB CHECK では映画の状態を別テーブルから参照できないため、また DDD として状態整合は集約/UseCase に置く)。具体的には、将来 UnpublishMovie 系 UC を作る場合は同 Tx 内で関連 OPEN screenings を `CANCELED` 化する責務を持たせる。

#### 本案件のスコープ

- **UnpublishMovie はスコープ外**。一度 PublishMovie した映画は提出時点まで `is_published=1` を維持する前提
- → コード経路上 `is_published=1→0` を起こせない → 「`is_published=0 + status=OPEN`」状態が発生しない
- → CT-04 / CT-05 などの Query は `is_published` を再 filter する必要がない (B の現実装で正しい)

#### 将来 admin UC を本格実装する場合

UnpublishMovie (仮 CT-13) を追加するときは:
1. UseCase に「同 Tx で関連 OPEN screenings を `CANCELED` 化 + 必要なら CT-12 と同様の返金連鎖」を明記
2. Defense-in-depth として CT-04 (`ListUpcomingScreenings`) の JOIN に `AND m.is_published = 1` を追加するかは別途判断 (書込側で守れていれば原則不要)

---

## 3. reservation (予約画面 — 座席選択) 🅲

| ID | UC名 | 種別 | 概要 | 不変条件 / Tx観点 |
|---|---|---|---|---|
| RV-01 | LoadSeatMap | Qry | 上映会の `seat_states` 全件 + Seat レイアウト情報 | UI描画用 |
| RV-02 | HoldSeats | **Cmd 🔒** | 選んだ複数座席を HOLD する (期限10分) | ★ダブルブッキング防止の中核。同時実行で1リクエストだけ成功する |
| RV-03 | ReleaseHold | Cmd 🔒 | 自Reservation の HOLD を AVAILABLE に戻す | 自分の予約のみ解放可 |
| RV-04 | ExtendHold | Cmd 🔒 | 期限延長 (任意) | HOLD 中のみ |
| RV-05 | ExpireHolds | **Cmd (Job) 🔒** | バックグラウンドで期限切れ HOLD を EXPIRED に / `seat_states` を AVAILABLE に戻す | 1秒間隔で起動。冪等。 |
| RV-06 | GetMyActiveReservation | Qry | 自分の HOLD 中予約取得 | |

**画面**: `seat_select.fxml` (グリッドでクリック→ HOLD)
**Tx境界**:
- RV-02 = `BEGIN IMMEDIATE` で `seat_states` を `UPDATE WHERE status='AVAILABLE'` の影響行数で衝突検出。0件ならRollback。Reservation INSERT も同一Tx。
- RV-05 = 期限切れの全件を1Txで処理 (バッチサイズ100)。

**`testing.md` の対象**: RV-02 / RV-05 はACID 4要件すべてのテストを書く。

---

## 4. ordering (チェックアウト) 🅲

| ID | UC名 | 種別 | 概要 | 不変条件 / Tx観点 |
|---|---|---|---|---|
| OR-01 | StartCheckout | Qry | HOLD中Reservation → 合計金額/座席詳細を返す | 金額再計算 (DBの最新価格基準) 💰 |
| OR-02 | Checkout | **Cmd 🔒💰** | 1つのTxで Order作成 → Payment実行 → Reservation→CONFIRMED → SeatState→SOLD → Ticket発行 → Outbox記録 | **本案件最重量Tx**。例外なら全Rollback。 |
| OR-03 | CancelOrder | Cmd 🔒💰 | 注文取消 → Refund (Mock) → Ticket→CANCELED → SeatState→AVAILABLE | 上映前のみ |
| OR-04 | RefundOrder | Cmd 🔒💰 | (admin/Screening Cancel連鎖) Refund のみ | 冪等 (二重Refund禁止) |
| OR-05 | GetMyOrders | Qry | 自分の注文履歴 | |

**Payment実装**: 課題スコープでは外部決済ナシ。`MockPaymentGateway` が `success_rate` パラメータでランダム失敗を返す → 失敗時の Tx Rollback テストに使う。

**金銭整合 💰**:
- 価格は `seat_states.price` が SoT。Checkout時に `SUM(price)` を再計算し、Order.total_amount と一致を assert。
- Money は `record Money(long minorUnits, Currency currency)` で扱い、加減算メソッドのみ提供。`int` のままだと丸め事故が起きやすい。

---

## 5. ticketing (チケット画面) 🅰

| ID | UC名 | 種別 | 概要 | 不変条件 |
|---|---|---|---|---|
| TK-01 | ListMyTickets | Qry | 自分のチケット一覧 (ACTIVE / USED / CANCELED) | |
| TK-02 | GetTicketDetail | Qry | チケット1件 (映画/座席/QR文字列) | |
| TK-03 | MarkUsed | Cmd 🔒 | (もぎり相当) ACTIVE → USED | 上映時間内のみ。冪等 |
| TK-04 | (admin) RevokeTicket | Cmd 🔒💰 | 不正発見時の取消 | 同時にOR-03 を呼ぶ |

**画面**: `tickets.fxml` (一覧 + 詳細パネル)
**QR**: 文字列のみ (実画像は描かなくてよい)。`ticketId` を base64url で表示。

---

## 6. cross-cutting (shared)

| ID | 機能 | 採用パターン |
|---|---|---|
| SH-01 | DI Container | Singleton + Registry |
| SH-02 | UnitOfWork / TxManager | Template Method + Command |
| SH-03 | DomainEventBus | Observer + Outbox |
| SH-04 | Clock (テスト用に固定可) | Strategy |
| SH-05 | IdGenerator (UUIDv7) | Strategy |
| SH-06 | CurrentUserHolder | Singleton (Session) |

---

## 7. ユースケース×コンテキスト依存マトリクス

| UC | identity | catalog | reservation | ordering | ticketing |
|---|:-:|:-:|:-:|:-:|:-:|
| RV-02 HoldSeats | R | R | **W** | - | - |
| OR-02 Checkout | R | R | **W** | **W** | **W** |
| OR-03 CancelOrder | R | - | **W** | **W** | **W** |
| TK-03 MarkUsed | R | R | - | - | **W** |

> **Checkout (OR-02) は5コンテキストを横断する**。タスク分割はこのUCを Person C 専担にし、依存される側 (A/B) はinterfaceを先に固めて公開する。
> 詳しくは `task_split.md`。
