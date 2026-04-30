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

1. **ブランチ命名**: `<owner>/<bc>/<feature>` 例: `c/reservation/hold-seats`
2. **小さく頻繁にPR**: 1PR < 400行目安。WIP は draft で。
3. **共有ファイル変更は予告**: `build.gradle.kts` / `App.java` / `application.properties` を触る PR は Slack/Issue に「touching shared X」と書く。
4. **migrate ファイルは `V###` 番号衝突を避ける**:
   - 100の位を担当に予約: 0xx=shared, 1xx=catalog, 2xx=reservation, 3xx=ordering, 4xx=ticketing
   - 同担当内では時系列で +1
5. **rebase before push**: main を rebase で取り込んでから push (merge commit を作らない)
6. **ArchUnit Rule** (`testing.md` 参照) で BC 越境参照を **CI で機械検出**。レビュー疲労を避ける。
7. **Daily 5分同期**: 翌日触る共有ファイルだけ口頭で宣言。
8. **PR テンプレ** に「触ったshared file」「変更したinterface」「追加マイグレ番号」を明記する欄を設ける。

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
