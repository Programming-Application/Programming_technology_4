<!-- 詳細仕様: docs/spec.md / docs/architecture.md / docs/testing.md / CLAUDE.md -->

## 概要 (What / Why)
- (1-3行で)

## 影響範囲

### 触った Bounded Context
- [ ] identity   (Person A)
- [ ] catalog    (Person B)
- [ ] reservation (Person C)
- [ ] ordering   (Person C)
- [ ] ticketing  (Person A)
- [ ] shared / cross-cutting

### 触った shared file (`docs/task_split.md` §2 参照)
- (なし / 触ったパスを列挙)

### 追加 / 変更した DB マイグレーション
- (例: `V021__add_seat_state_index.sql`)

### 追加 / 変更した interface (Repository / DTO record / UseCase シグネチャ)
- (なし / 列挙)

## テスト (`docs/testing.md` 準拠)

### 書込ユースケース or 金銭が絡むなら必須
- [ ] **Unit Test** (`*Test`) — domainロジック
- [ ] **Repository Test** (`*IT`) — CHECK / UNIQUE / FK 直接assert
- [ ] **Tx Test** (`*TxTest`) — Atomicity / Consistency / Durability
- [ ] **Concurrency Test** (`*ConcurrencyTest`) — Isolation / 衝突解消
- [ ] 💰 金銭整合チェックリスト (`docs/testing.md` §3) を網羅
- [ ] (関連UCがあれば) ArchUnit ルール追加

### 自動チェック
- [ ] `./gradlew check` がローカルで green
- [ ] CI green

## レビュー観点 (任意)
- 特に注目してほしい箇所があれば

## スクリーンショット (UI変更時のみ)
