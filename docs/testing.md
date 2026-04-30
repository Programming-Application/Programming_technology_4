# テスト方針 (ACID / 同時実行 / 金銭整合)

> 本ドキュメントは **Claude Code への厳格な指示書** であると同時に、人間の開発者にとっても テストコード作成のチェックリストを兼ねる。
> CLAUDE.md から本ファイルを参照しており、**新規/変更コードを書くときは必ずこの方針に従う**こと。

---

## 0. 大原則

1. 本システムは **金銭** を扱う。Tx が壊れたら直接損害が発生する想定で書く。
2. **「コードが動いた」≠「テストが書けた」**。状態遷移と境界条件をすべて網羅して初めて完了。
3. テストは **書込ユースケース1本につき必ず 4 種類**: Unit / Repository / **Tx (ACID)** / Concurrency。
4. **金銭が関わる UC** には 💰 チェックリスト全項目をクリアする。
5. テストはランダム順実行で grenn であること (`@TestMethodOrder` を依存にしない)。
6. テスト間で DB 状態を共有しない (各テストは隔離)。

---

## 1. テストの階層

| 層 | 目的 | 場所 | 速度目安 |
|---|---|---|---|
| **Unit** | 純粋ドメインロジック (集約の不変条件 / 状態遷移) | `*DomainTest` | <10ms |
| **Repository** | JDBC SQL の正しさ / CHECK / UNIQUE / FK | `*RepositoryIT` | 〜100ms |
| **Application (Tx)** | UseCase 実行で ACID が成立する | `*UseCaseTxTest` | 〜500ms |
| **Concurrency** | 同時実行時の Isolation / 衝突解消 | `*ConcurrencyTest` | 〜2s |
| **ArchUnit** | レイヤ依存違反の機械検出 | `arch/*ArchTest` | <1s |
| **End-to-end (任意)** | UI も含む通し動作 | `e2e/*` | 〜数秒 |

---

## 2. ACID テスト テンプレート

書込ユースケースは **必ず**以下4観点を1本ずつ持つ。`@Nested` で1テストクラスにまとめてよい。

### 2.1 A — Atomicity (原子性)

> Tx 中で例外が起きたら**全変更が Rollback** されること。中途半端な状態が残らない。

```java
@Test
void checkout_when_payment_fails_rolls_back_all_writes() {
    // given: HOLD中の予約 + 決済が必ず失敗するMockGateway
    var reservation = seeds.holdSeats(user, screening, seats(3));
    paymentGateway.alwaysFail();

    var before = db.snapshot();           // testkit: 全テーブルダンプ

    assertThatThrownBy(() -> checkout.execute(new CheckoutCommand(reservation.id())))
        .isInstanceOf(PaymentFailedException.class);

    var after  = db.snapshot();
    assertThat(after).isEqualTo(before);  // 何も書かれていない (orders/payments/tickets/seat_states すべて)
}
```

**最低書く Atomicity ケース**:
- [ ] 業務例外 (PaymentFailed, InvalidState) で全 Rollback
- [ ] 技術例外 (`SQLException` 注入) で全 Rollback
- [ ] **Tx 途中で**プロセス強制終了 (`Runtime.halt`) → 再起動後 DB が中間状態でない (Repository テストで `BEGIN; INSERT; -- COMMITしない`)

### 2.2 C — Consistency (整合性)

> 全 CHECK / FK / UNIQUE / アプリ不変条件を Tx 後も満たすこと。

```java
@Test
void after_checkout_screening_counters_match_seat_states() {
    var s = seeds.openScreening(seats=10);
    seeds.holdSeats(...).then(checkout(...));

    var counts = db.queryGroupCounts("seat_states", "screening_id=?", s.id());
    var screening = repo.find(s.id()).orElseThrow();
    assertThat(screening.availableSeatCount()).isEqualTo(counts.get("AVAILABLE"));
    assertThat(screening.reservedSeatCount() ).isEqualTo(counts.get("HOLD"));
    assertThat(screening.soldSeatCount()     ).isEqualTo(counts.get("SOLD"));
}
```

**最低書く Consistency ケース**:
- [ ] 各テーブルの **CHECK 制約**を破ろうとして失敗する (`assertThrows(SQLException.class)`)
- [ ] FK 違反 (存在しない `screening_id` で予約) で失敗
- [ ] `tickets uq_tickets_active_seat` で同一座席に2 枚 ACTIVE が物理的に作れない (★最終防壁)
- [ ] `seat_states.CHECK` の状態×関連列の組合せがすべて検証 (status×reservation_id×ticket_id の表)
- [ ] アプリ不変: `screenings` の3カウンタ合計 = `seats` 枚数 を全UC実行後にassert

### 2.3 I — Isolation (隔離性)

> 同時実行で衝突が起きても**矛盾した結果を返さない**こと。

```java
@Test
void two_users_holding_same_seat_only_one_succeeds() throws Exception {
    var sid = seeds.openScreening();
    var seat = seats(1);

    var pool = Executors.newFixedThreadPool(2);
    var barrier = new CyclicBarrier(2);
    var f1 = pool.submit(() -> { barrier.await(); return holdSeats.execute(cmd(userA, sid, seat)); });
    var f2 = pool.submit(() -> { barrier.await(); return holdSeats.execute(cmd(userB, sid, seat)); });

    var results = List.of(safe(f1), safe(f2));
    var successes = results.stream().filter(Result::isOk).count();
    var failures  = results.stream().filter(Result::isErr).count();

    assertThat(successes).isEqualTo(1);
    assertThat(failures ).isEqualTo(1);

    var seatState = repo.find(sid, seat).orElseThrow();
    assertThat(seatState.status()).isEqualTo(HOLD);
    // 失敗側は ConflictException で AVAILABLE のままになっていないこと (=他人のHOLDが見える)
}
```

**最低書く Isolation ケース**:
- [ ] **Lost Update**: 同一 Reservation を 2 スレッドが同時に Confirm → 楽観ロックで片方失敗
- [ ] **Dirty Read**: 同時実行中の中間状態が他Txから見えないこと (SQLite WALでは defer_foreign_keys 有無で挙動確認)
- [ ] **Phantom-like**: 在庫切れ寸前で同時HOLD → 必ず空席数まで成功・残りは失敗
- [ ] HOLD 期限切れと Checkout が同時に起きた場合の競合 (どちらが勝つか定義 → そのとおりになる)
- [ ] 100 ユーザが同一座席を奪い合うストレステスト (1スレッドのみSOLD化される / 残りはConflict)

### 2.4 D — Durability (永続性)

> COMMIT した変更は**接続を閉じても**残ること。

```java
@Test
void committed_order_survives_reconnect() {
    Path dbPath = tempDb();
    try (var conn1 = db.openFile(dbPath)) {
        runCheckout(conn1, ...);     // COMMIT 済
    }
    try (var conn2 = db.openFile(dbPath)) {
        var orders = orderRepo(conn2).findByUser(user);
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).status()).isEqualTo(CONFIRMED);
    }
}
```

**最低書く Durability ケース**:
- [ ] COMMIT 後に接続Close → 別接続で同データが見える
- [ ] WAL ファイル (`*.db-wal`) 残存中にプロセス kill → 次起動で WAL リカバリ後にデータが残る
- [ ] (任意) `PRAGMA synchronous = OFF` での比較 (危険性デモ)

---

## 3. 金銭整合 💰 チェックリスト

> 金額に触るすべてのUC (Checkout, Refund, Cancel) に必ず適用する。

- [ ] **Money 型のみで計算する** (`int` の生加算禁止。`Money.plus(other)` のみ)
- [ ] 通貨ミスマッチで例外 (`Money(JPY).plus(Money(USD))` → `MismatchedCurrencyException`)
- [ ] 金額の **再計算検証**: Order.total_amount は Tx 内で `seat_states.price` を再 SUM して assert
- [ ] **負の金額が許容されない**: `Money(-1)` で例外 / DB CHECK でも防御
- [ ] **二重課金不可**: 同一 Reservation を2回 Checkout → 2回目は `OrderAlreadyExistsException` (`orders.reservation_id UNIQUE`)
- [ ] **二重返金不可**: 同一 Order を 2 回 Refund → 2回目は `RefundAlreadyDoneException` (`refunds.order_id UNIQUE`)
- [ ] **失敗時の金銭副作用ゼロ**: 決済失敗時 `payments` も `refunds` も 1 行も作らない (Rollback で消える)
- [ ] **税/手数料を計算するなら別 VO に切る** (今回は税なし)
- [ ] **冪等キー**: Refund に冪等キーを持たせ、二重実行で同じ結果を返す

---

## 4. テストインフラ (testFixtures)

`src/testFixtures/java/com/theater/testkit/` に以下を配置 (Person A 提供):

```java
public final class Db {
    public static DataSource openTempFile();      // /tmp/theater-test-{uuid}.db を新規
    public static DataSource openInMemoryShared(); // 同一接続を全Repoで共有 (in-memory用)
    public static Map<String, Map<String, Long>> snapshot(DataSource);  // 全テーブル件数+ハッシュ
}

public final class FixedClock implements Clock {
    public FixedClock(Instant now);
    public void advance(Duration d);
}

public final class Seeds {
    public Seeds(DataSource ds, Clock clock, IdGenerator ids) { ... }
    public User user(String email);
    public Movie movie(String title);
    public Screen screen(int rows, int cols);
    public Screening openScreening(Movie m, Screen s);
    public Reservation holdSeats(User u, Screening s, List<SeatId> seats);
}

public final class FakePaymentGateway implements PaymentGateway {
    public void alwaysSucceed();
    public void alwaysFail();
    public void failNthCall(int n);
    public void delayMs(long ms);                  // 競合シミュレーション
}
```

---

## 5. JUnit 規約

- JUnit 5 + AssertJ + Mockito + Awaitility
- クラス名は `*Test` (Unit) / `*IT` (Integration / Repository) / `*TxTest` (Tx) / `*ConcurrencyTest`
- メソッド名は `subject_when_condition_then_outcome` (snake)
- `@Nested` で「正常系/異常系/境界」を構造化
- **`Thread.sleep` 禁止**。待機は `Awaitility.await().atMost(...)`
- **`@Disabled` を main にコミットしない**。残すなら理由 + Issue 番号
- 1 テスト = 1 assert ブロック (AssertJ の `assertThat().satisfies()` で複合は可)
- ランダム性は seed を固定: `Random r = new Random(42)`
- Junit `@RepeatedTest(20)` を Concurrency テストに付ける (flaky 検出)

---

## 6. ArchUnit ルール (依存違反の機械検出)

`src/test/java/com/theater/arch/LayerArchTest.java` に以下を必ず書く:

```java
@AnalyzeClasses(packages = "com.theater")
class LayerArchTest {
  @ArchTest
  static final ArchRule layered =
      layeredArchitecture().consideringAllDependencies()
        .layer("UI").definedBy("..ui..")
        .layer("Application").definedBy("..application..")
        .layer("Domain").definedBy("..domain..")
        .layer("Infrastructure").definedBy("..infrastructure..")
        .whereLayer("UI").mayNotBeAccessedByAnyLayer()
        .whereLayer("Application").mayOnlyBeAccessedByLayers("UI")
        .whereLayer("Domain").mayOnlyBeAccessedByLayers("UI","Application","Infrastructure")
        .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer();

  @ArchTest
  static final ArchRule no_cross_bc_domain =
      noClasses().that().resideInAPackage("com.theater.(*).domain..")
        .should().dependOnClassesThat().resideInAPackage("com.theater.(*).domain..")
        // 自BC内はOK、他BCのdomainは禁止
        .as("Bounded Context 越境参照禁止 (DTO経由にせよ)");

  @ArchTest
  static final ArchRule infra_impl_package_private =
      classes().that().resideInAPackage("..infrastructure..")
        .and().haveSimpleNameEndingWith("Repository")
        .should().notBePublic();
}
```

---

## 7. テスト命名/タグ

- `@Tag("unit")`  / `@Tag("integration")` / `@Tag("concurrency")` / `@Tag("slow")`
- CI では `unit` を最優先で実行 / `slow` は別ジョブ

---

## 8. 「Claudeへの指示」サマリ (CLAUDE.md と一致)

新しい書込UseCase / 既存UC変更を実装するときは、**必ず**:

1. Unit テスト (`*Test`) を 1 本以上 (正常+異常+境界)
2. Repository テスト (`*IT`) で SQL の意図 (CHECK/UNIQUE/FK) を直接assert
3. Tx テスト (`*TxTest`) で **A / C / D** を最低1ケースずつ (Iは Concurrency側)
4. Concurrency テスト (`*ConcurrencyTest`) で **I** を最低1ケース
5. 💰 が絡む UC は §3 のチェックリスト全項目をテストで担保
6. ArchUnit が green であること

これらが揃っていない PR は**マージしない**。Claude が自動で書く際も省略しない。
