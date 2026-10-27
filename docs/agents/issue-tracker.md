# Issue tracker: GitHub

Issues, specs and tickets for this repo live as GitHub issues. Use the `gh` CLI.

- **Repo**: `Land-Fairy/tiny-spring-tdd`
- **Auth**: `gh` 已登录 `Land-Fairy`（scopes: `repo`, `workflow`, `gist`, `read:org`）

## Conventions

- **Create an issue**: `gh issue create --title "..." --body-file <file>`
- **Read an issue**: `gh issue view <number> --json number,title,body,labels,comments`
- **List issues**: `gh issue list --state open --json number,title,labels,assignees`
- **Comment**: `gh issue comment <number> --body-file <file>`
- **Label**: `gh issue edit <number> --add-label "..."`
- **Close**: `gh issue close <number> --comment "..."`

## Triage labels

本仓使用 5 个规范角色，见 [triage-labels.md](triage-labels.md)。

## Pull requests as a triage surface

**no** — 本仓不接受外部 PR 作为需求来源。

## When a skill says "publish to the issue tracker"

Create a GitHub issue with the appropriate label.

## When a skill says "fetch the relevant ticket"

`gh issue view <n> --json number,title,body,labels,comments`.

## Wayfinding operations

本仓不使用 wayfinder map。执行期进度用 **Milestone + Project board** 表达，不用 decision map。

## Blocking edges

GitHub 原生 issue dependencies 可用（已实测）：

```bash
# 取 blocker 的数据库 id（不是 #number）
gh api repos/Land-Fairy/tiny-spring-tdd/issues/<n> --jq .id

# 建立 blocked_by 边
gh api --method POST \
  repos/Land-Fairy/tiny-spring-tdd/issues/<child>/dependencies/blocked_by \
  -F issue_id=<blocker-db-id>
```

查询某 issue 是否被阻塞：`gh api repos/.../issues/<n> --jq .issue_dependencies_summary.blocked_by`

## Sub-issues

本机 `gh` 为 2.46，**不支持** `--parent` / `--add-sub-issue`（需 2.94+）。使用 REST：

```bash
gh api --method POST repos/Land-Fairy/tiny-spring-tdd/issues/<parent>/sub_issues \
  -F sub_issue_id=<child-db-id>
```

child 的 `sub_issue_id` 同样是**数据库 id**。

## ⚠️ 网络注意

本机到 github.com 的 **HTTP/2 连接会被中断**（`GnuTLS recv error (-110)`）。
`gh` CLI（走 api.github.com）正常，但 `git clone/fetch/push` **必须强制 HTTP/1.1**：

```bash
git config http.version HTTP/1.1   # 已在仓内配置
```

新 clone 时：`git -c http.version=HTTP/1.1 clone ...`
