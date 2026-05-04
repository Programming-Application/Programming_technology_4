# CLAUDE.md — theater project

> このファイルは Claude Code が本リポジトリで作業するときに**必ず最初に読む指示書**である。
> 人間も同様にこのファイルを起点に `docs/` を参照する。

---

## 1. プロジェクト概要

- **何**: 映画館チケット予約システム (学校課題)
- **言語/UI/DB**: **Java 17 / JavaFX 21 / SQLite 3.45+**
- **アーキテクチャ**: DDD (戦術設計) + 自作DI/Tx (GoFパターン顕在化)
- **ビルド**: Gradle (Kotlin DSL)
- **特徴**: 金銭(座席料金) を扱うため ACID/同時実行を厳格にテストする
- **チーム**: 3人 (A / B / C) で feature 単位に並列開発

詳細仕様は `docs/spec.md` (要件) を参照。

---

## 2. 必読ドキュメント (作業前に該当章を読む)

| 状況 | 読むべき docs |
|---|---|
| はじめて触る | `docs/architecture.md` 全体 → 該当 BC の `docs/features.md` |
| DBスキーマを変える | `docs/data_model.md` (V###番号と所有BC) |
| 自分の担当を確認 | `docs/task_split.md` (ファイル所有権マップ) |
| **テストを書く / 書込UCを作る** | `docs/testing.md` を**全文**読む (このファイルは規約。省略不可) |
| Gradle / CI / Lint を触る | `docs/ci_lint.md` |
| 元のFirestore版を参考にしたい | `docs/data_structure.md` (legacy reference; 正本は data_model.md) |

---

## 3. 不変の作業ルール (Claude も人間も)

### 3.1 アーキテクチャの不変条件 (違反は ArchUnit が CI で落とす)

- `domain` レイヤは他レイヤ (application/infrastructure/ui) に依存しない
- `application` は `domain` のみ参照
- `infrastructure` は `domain` の interface を実装する
- `ui` (JavaFX) は `application` のみ呼ぶ。`domain` を直接触らない
- **Bounded Context 越境は禁止**: 他 BC の `domain.*` クラスを参照しない。必要なら `application.dto.*` (record) でやり取り

### 3.2 トランザクション (`docs/testing.md` を遵守)

- **書込ユースケース = 1 Tx**。`shared.tx.UnitOfWork` または `TransactionalUseCase<C,R>` 経由で実行する
- 外部呼出 (PaymentGateway 等) を含む Tx は意図して短く保つ
- 楽観ロック失敗 (`OptimisticLockException`) は呼び出し側で再試行 or ユーザに伝搬
- 高頻度衝突パス (`HoldSeats`) は `BEGIN IMMEDIATE` を使用

### 3.3 SQLite 接続オプション (起動時に**必ず**)

- `PRAGMA foreign_keys = ON`
- `PRAGMA journal_mode = WAL`
- `PRAGMA busy_timeout = 5000`
- `PRAGMA synchronous = NORMAL`

### 3.4 Money / 金銭

- **`int` で金額を持たない**。`shared.kernel.Money` (record) を使う
- 通貨ミスマッチ加減算は例外
- 負金額禁止 (アプリ + DB CHECK)
- 二重課金/二重返金は DB UNIQUE で防御済 — テストで必ず叩く

### 3.5 Java 17 機能を積極使用 (課題要件)

- `record` (Command / DTO / VO)
- `sealed interface` + `permits` (状態 / Result)
- `switch` の pattern matching
- text block (SQL)
- これらを使えるところで `class` を選ばない

### 3.6 GoF パターンを名指しで使う (課題要件)

`docs/architecture.md` §5 のパターン表を実装する。
レポート提出時に「どのファイルでどのパターンを採用したか」を列挙できるように、各実装クラスに **Javadoc 1行** で `// pattern: Singleton` のように明記してよい (整形では消えない)。

---

## 4. テストを書くときの絶対ルール (詳細は `docs/testing.md`)

新規/変更の書込ユースケースには **必ず以下4種類**のテストを書く:

1. **Unit Test** (`*Test`) — 純粋 domain ロジック
2. **Repository Test** (`*IT`) — JDBC SQL の CHECK/UNIQUE/FK を直接 assert
3. **Tx Test** (`*TxTest`) — Atomicity / Consistency / Durability を最低1ケースずつ
4. **Concurrency Test** (`*ConcurrencyTest`) — Isolation / 衝突解消を最低1ケース

**金銭を扱うUC**には追加で `docs/testing.md` §3 の **💰 チェックリスト全項目** を担保するテストを書く。

> これらが揃わないコードは Claude が書いても**未完了**として扱う。
> 実装PRに `// TODO: テスト後で書く` を残してはならない。

---

## 5. ファイル所有権 (Conflict 回避)

`docs/task_split.md` のファイル所有権マップに従う。要点:

| パス | 所有 |
|---|---|
| `src/main/java/com/theater/identity/**` `ticketing/**` `shared/**` `App.java` | A |
| `src/main/java/com/theater/catalog/**` | B |
| `src/main/java/com/theater/reservation/**` `ordering/**` | C |
| Migration `V001/V040` | A / `V010` | B / `V020/V030` | C |

**所有外のファイルを編集する必要が出たとき** (Claude も同様):
1. 何を変えたいかを PR description に明記
2. 所有者の review approval が必須
3. `App.java` のような共有ファイルは Issue で予告してから着手

---

## 6. ブランチ運用

**`main` ← `develop` ← `feat/*`** の3層運用 (詳細: [`CONTRIBUTING.md`](CONTRIBUTING.md))。

- ブランチ命名: `feat/<owner>/<bc>/<topic>` 例: `feat/c/reservation/hold-seats`
  - 修正は `fix/...`、ドキュメントは `docs/...`、ビルド/CIは `build/...` `ci/...`
- **PR の base ブランチは原則 `develop`** (feat/* → develop)
- `develop` → `main` はリリース PR のみ (fast-forward, linear history)
- `main` への直 push 禁止 / force push 禁止 / `--no-verify` 禁止
- 必須 CI: `build & unit/integration test` / `lint`
- 承認 1 名以上必須
- 1 PR < 400 行を目安
- Conflict 解消は `git rebase develop` (merge commit を作らない)
- PR テンプレに必須記載: **触ったshared file / 追加 V### / 変更した interface**

---

## 7. 開発コマンド

```bash
./gradlew spotlessApply                             # 整形 (コミット前)
./gradlew check                                     # lint + 全テスト + coverage
./gradlew test --tests "*ConcurrencyTest"           # 同時実行テストだけ
./gradlew test -Dtest.repeat=20 --tests "...HoldSeatsConcurrencyTest"
./gradlew slowTest                                  # 重いテスト
./gradlew run                                       # JavaFX 起動
```

---

## 8. Claude Code への指示 (作業手順)

> 「実装してほしい」と言われた場合の標準フロー。

1. **タスクを `TaskCreate` に落とす** (3ステップ以上の作業なら必須)
2. 該当 BC のファイル所有権を `docs/task_split.md` で確認
3. 実装するUCを `docs/features.md` で ID 確認 → 不変条件を読む
4. 必要なら `docs/data_model.md` のスキーマと SQL パターンを参照
5. **コードを書いたら直後に同コミットでテストを書く** (Unit/Repo/Tx/Concurrency)
6. `./gradlew check` を通すまでは完了と呼ばない
7. PR description に下記を必ず書く:
   - 触ったshared file (なし/あり: 一覧)
   - 追加 migration 番号 (V###)
   - 変更/追加した interface
   - 担保したテスト (チェックリスト形式)

---

## 9. 禁止事項

- `// TODO` で未実装機能をコミット (本案件では DoD 不適合)
- `@Disabled` / `@Ignore` を理由なく追加 (理由+Issueの記載必須)
- `Thread.sleep` をテストで使用 (`Awaitility` を使う)
- DB操作で生 `int` の金額計算 (Money 型必須)
- BC 越境の `domain.*` 直接参照
- main への force push / `--no-verify` でフックスキップ
- `domain.*` で例外を String message のみで投げる (例外型を作る)

---

## 10. 設計判断の追記

設計を変える決定をしたら、`docs/architecture.md` §9 の「設計判断ログ」に1行追記する。
特に以下は理由とセットで残す:
- レイヤ依存ルールの例外を作ったとき
- ArchUnit ルールを緩めたとき
- 新しいライブラリを `libs.versions.toml` に追加したとき
- Tx 境界の方針 (REQUIRED / REQUIRES_NEW) を変えたとき
