#!/usr/bin/env bash
#
# 真实 Spring 只读参照仓管理（issue #39）。
#
# 为什么需要它：每章正文的「🔍 真实 Spring 里它在哪」段必须给出全限定类名，
# 凭记忆写必然出错。本脚本把「参照仓在哪、用的是哪个 commit」变成一条命令可查的事实，
# 而不是每章靠印象。
#
# 用法：
#   scripts/refs.sh fetch              浅克隆或复用参照仓；无网时降级提示，不失败
#   scripts/refs.sh fetch --history    加深历史窗口（git blame / git log 追设计意图需要）
#   scripts/refs.sh status             打印现状：路径 / commit hash / 历史窗口 / 正常还是降级
#   scripts/refs.sh path               只打印参照仓路径，供其他脚本取用
#
# 为什么要分两档：
#   - 找全限定类名只需要工作树，`--depth 1` 把 1 GB 级历史降到 ~100 MB。
#   - 但浅克隆里 git blame 会把每一行都算到**边界提交**头上（显示形如 `^ba39385c`），
#     完全没有信息量。要追"这行为什么这么写"，必须有历史窗口。
#   - 实测：本仓对 github.com 做 `git fetch --unshallow` 会中途 TLS 断开；
#     而 `--deepen=N` 分块拉取能成功。所以这里用 deepen 而不用 unshallow。
#   - 再往前的历史（十几年前的提交）在本地补不齐，请用 GitHub 网页 blame，
#     见 docs/chapters/00-methodology.md 第 6 节。
#
# 设计约束：本脚本是 ch01 的硬前置。若它只能在有网环境"成功"，
# 整条章节链在 CI 或无网环境中就会死锁。因此除用法错误外，退出码恒为 0。

set -euo pipefail

REFS_DIR="${SPRING_REFS_DIR:-$HOME/refs/spring-framework-6.2.x}"
REPO_URL="https://github.com/spring-projects/spring-framework"
REF_NAME="${SPRING_REFS_REF:-6.2.x}"
REFS_DEPTH="${SPRING_REFS_DEPTH:-300}"

# github.com 在本机的 HTTP/2 会断（GnuTLS recv error / unexpected disconnect），
# 统一走 HTTP/1.1 并给低速连接设阈值，避免大仓克隆半路卡死。
GIT_HTTP_OPTS=(-c http.version=HTTP/1.1 -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=120)

DEGRADED_HINT="[refs] 未找到本地参照仓，@see 全限定名需对照 Spring 6.2.x 官方 Javadoc 核对"

usage() {
	cat <<'EOF'
用法: scripts/refs.sh <command>

命令:
  fetch              浅克隆或复用真实 Spring 参照仓（只读用途）
  fetch --history    加深到 SPRING_REFS_DEPTH 个提交，让 git blame / git log 可用
  status             打印路径、commit hash、历史窗口大小、正常/降级状态
  path               只打印参照仓绝对路径

环境变量:
  SPRING_REFS_DIR    参照仓位置，默认 ~/refs/spring-framework-6.2.x
  SPRING_REFS_REF    参照的分支或 tag，默认 6.2.x
  SPRING_REFS_DEPTH  历史窗口（提交数），默认 300
EOF
}

has_refs() {
	[ -d "$REFS_DIR/.git" ]
}

refs_hash() {
	git -C "$REFS_DIR" rev-parse HEAD 2>/dev/null || echo unknown
}

commit_count() {
	git -C "$REFS_DIR" rev-list --count HEAD 2>/dev/null || echo 0
}

degrade() {
	echo "$DEGRADED_HINT"
	echo "[refs] 降级 $1"
}

cmd_status() {
	if ! has_refs; then
		degrade "path=$REFS_DIR（不存在）"
		return 0
	fi
	local count
	count=$(commit_count)
	echo "[refs] 正常  path=$REFS_DIR  ref=$REF_NAME  commit=$(refs_hash)"
	if [ "$count" -le 1 ]; then
		echo "[refs] 历史  1 个提交（浅克隆）；git blame 只会显示边界提交，先跑 fetch --history"
	else
		echo "[refs] 历史  $count 个提交；git blame 仅覆盖这段窗口，更早的行仍会落到边界提交"
	fi
	return 0
}

deepen_to() {
	local target="$1" current
	current=$(commit_count)
	if [ "$current" -ge "$target" ]; then
		return 0
	fi
	echo "[refs] 正在加深历史: $current -> $target 个提交"
	git -C "$REFS_DIR" "${GIT_HTTP_OPTS[@]}" fetch "--deepen=$((target - current))"
}

cmd_fetch() {
	local want_history="no"
	if [ "${1:-}" = "--history" ]; then
		want_history="yes"
	fi

	if has_refs; then
		if [ "$want_history" = "yes" ]; then
			if ! deepen_to "$REFS_DEPTH"; then
				degrade "加深历史失败；仍可查类名，但 git blame 不可用"
				return 0
			fi
		fi
		echo "[refs] 复用已有参照仓  commit=$(refs_hash)"
		return 0
	fi

	mkdir -p "$(dirname "$REFS_DIR")"
	local depth=(--depth 1)
	if [ "$want_history" = "yes" ]; then
		depth=("--depth=$REFS_DEPTH")
	fi

	echo "[refs] 正在克隆 $REPO_URL ($REF_NAME) -> $REFS_DIR"
	if ! git "${GIT_HTTP_OPTS[@]}" clone "${depth[@]}" --branch "$REF_NAME" \
		"$REPO_URL" "$REFS_DIR"; then
		rm -rf "$REFS_DIR"
		degrade "克隆失败（可能无网络）。这不阻断构建，但本章 🔍 段必须改引官方 Javadoc 并标注核对来源。"
		return 0
	fi
	echo "[refs] 完成  commit=$(refs_hash)"
	return 0
}

cmd_path() {
	echo "$REFS_DIR"
}

case "${1:-status}" in
fetch) cmd_fetch "${2:-}" ;;
status) cmd_status ;;
path) cmd_path ;;
-h | --help | help) usage ;;
*)
	echo "未知命令: $1" >&2
	usage >&2
	exit 2
	;;
esac
