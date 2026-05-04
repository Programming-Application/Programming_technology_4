# Contributing — branching, PR, and release flow

このリポジトリは **Git Flow ライク** の3階層ブランチ運用を採用する。
ルールに従わないと CI / branch protection で蹴られるので注意。

---

## 1. ブランチ階層

```
main      ← protected。常にデプロイ可能 (本案件はデプロイなしだが「課題提出版」)
  ↑
develop   ← 開発統合ブランチ。feat/* は必ずここに向ける
  ↑
feat/*    ← 各個人の作業ブランチ
hotfix/*  ← (任意) main から派生して main + develop に戻す緊急修正
```

- **main**: 直 push 禁止 (force push / 削除も禁止)。`develop` からの **fast-forward merge** のみ。常に CI が green。
- **develop**: 統合用。`feat/*` から PR 経由でマージ。`./gradlew check` が常時 green を期待。
- **feat/***: 個人作業用。短命 (1 PR = 1 ブランチ目安)。

> 例外: `chore/`, `docs/`, `build/`, `ci/`, `test/`, `refactor/` など Conventional Commits 系の prefix も `feat/*` と同じ扱いで OK。

---

## 2. ブランチ命名

| 用途 | パターン | 例 |
|---|---|---|
| 機能実装 | `feat/<owner>/<bc>/<short-topic>` | `feat/c/reservation/hold-seats` |
| 不具合修正 | `fix/<owner>/<bc>/<short-topic>` | `fix/b/catalog/screening-overlap` |
| ドキュメント | `docs/<short-topic>` | `docs/test-policy-update` |
| ビルド/CI | `build/<short>` `ci/<short>` | `build/upgrade-gradle-8.8` |
| リファクタ | `refactor/<owner>/<bc>/<short>` | `refactor/a/shared/uow-cleanup` |
| 緊急修正 | `hotfix/<short>` | `hotfix/v001-missing-fk` |

- `<owner>` は `a` / `b` / `c` (`docs/task_split.md` §1 の担当)。
- `<bc>` は `identity / catalog / reservation / ordering / ticketing / shared`。
- 短命を意識する。長く居座らせない。

---

## 3. 標準フロー

### 3.1 機能を作る

```bash
git checkout develop
git pull --ff-only origin develop
git checkout -b feat/c/reservation/hold-seats
# ... 実装 + テスト ...
./gradlew spotlessApply
./gradlew check                                # ローカルで green を確認
git push -u origin feat/c/reservation/hold-seats
gh pr create --base develop --fill             # base は必ず develop
```

PR テンプレ ([`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md)) の項目を埋める:
- 触った shared file
- 追加 V### マイグレーション
- 変更 interface
- テスト 4 種 (Unit / Repository / Tx / Concurrency) のチェック
- 💰 が絡むなら金銭整合チェックリスト

### 3.2 develop を main に上げる (リリース)

定期的に (Sprint末 / 課題提出前) `develop` の安定状態を `main` に取り込む:

```bash
gh pr create --base main --head develop --title "release: <yyyy-mm-dd>" --body "..."
```

- `main` は **fast-forward merge のみ** (linear history)
- 必須 CI が green であること
- レビュー承認 1 件以上 (branch protection で強制)

### 3.3 hotfix (任意)

main で発覚した致命バグを即座に直したい場合のみ:

```bash
git checkout main && git pull
git checkout -b hotfix/<short>
# ... fix + test ...
gh pr create --base main --head hotfix/<short>      # main へ
# main にマージ後、必ず develop にも取り込む:
gh pr create --base develop --head main --title "merge: hotfix back to develop"
```

---

## 4. PR / マージ規約

| 項目 | ルール |
|---|---|
| Base ブランチ | feat/* → **develop** / hotfix/* → main / release → main |
| サイズ目安 | < 400 行 / PR (大きすぎたら分割) |
| マージ方式 | **squash merge** (feat → develop) / **fast-forward (rebase) merge** (develop → main) |
| Conflict 解消 | `git rebase develop` (merge commit を生やさない) |
| Required checks | `build & unit/integration test` / `lint` (CI で green) |
| 承認数 | **1名以上** |
| Force push | 禁止 (個人ブランチを除く) |
| `main` への直 push | 禁止 |
| `--no-verify` | 禁止 |

---

## 5. コミットメッセージ

[Conventional Commits](https://www.conventionalcommits.org/) を採用:

```
<type>(<scope>): <subject>

<body>

<footer>
```

| type | 用途 |
|---|---|
| `feat` | 新機能 |
| `fix` | バグ修正 |
| `docs` | ドキュメントのみ |
| `build` | ビルド/依存関係 |
| `ci` | CI 設定 |
| `test` | テスト追加/修正 |
| `refactor` | 機能変化なしの整理 |
| `chore` | 上記以外の雑務 |

scope は BC 名 (`identity` / `catalog` / `reservation` / `ordering` / `ticketing` / `shared`) を推奨。

例:
```
feat(reservation): add HoldSeatsUseCase with optimistic seat locking

- Implements RV-02 from docs/features.md
- BEGIN IMMEDIATE + UPDATE WHERE status='AVAILABLE' rowsAffected check
- Adds Unit / Repository / Tx / Concurrency tests per docs/testing.md
```

---

## 6. ローカル開発のお作法

```bash
./gradlew spotlessApply       # コミット前に必ず
./gradlew check               # PR 出す前に必ず
./gradlew test --tests "*ConcurrencyTest"   # 同時実行系の flaky 検出
```

---

## 7. 詳細

| トピック | 参照先 |
|---|---|
| 何を作るか | [`docs/spec.md`](docs/spec.md) |
| アーキテクチャ / GoF / dir | [`docs/architecture.md`](docs/architecture.md) |
| ファイル所有権マップ | [`docs/task_split.md`](docs/task_split.md) §2 |
| テスト規約 (ACID / 💰) | [`docs/testing.md`](docs/testing.md) |
| CI / Lint 詳細 | [`docs/ci_lint.md`](docs/ci_lint.md) |
| Claude Code 向け指示 | [`CLAUDE.md`](CLAUDE.md) |
