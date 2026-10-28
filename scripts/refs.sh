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
# 本书全部 `@see` 行号与附录 C/E 的基准提交（spring-framework 6.2.x 上的一个固定点）。
# 校验它是否存在，只用来提醒「行号可能漂移」，绝不是构建闸门。
SPRING_REFS_PINNED="${SPRING_REFS_PINNED:-daf955157871e4ac6f192e06b71d6cc595eb979b}"
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
  verify             校验「本书引用的基准提交」是否存在；缺失只警告，不失败

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
	echo "[refs] 基准  $SPRING_REFS_PINNED（正文行号以此为准）"
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

# 校验基准提交是否在本地。缺失、不匹配都只是警告：
# 本书的 `mvn verify` 完全不依赖参照仓，把它做成死闸会让无网环境无法构建。
cmd_verify() {
	if ! has_refs; then
		degrade "无参照仓，无法校验基准提交 $SPRING_REFS_PINNED；不阻断构建"
		return 0
	fi
	if git -C "$REFS_DIR" cat-file -e "${SPRING_REFS_PINNED}^{commit}" 2>/dev/null; then
		local head
		head=$(refs_hash)
		if [ "$head" = "$SPRING_REFS_PINNED" ]; then
			echo "[refs] 基准提交在本地且工作树正指向它  $SPRING_REFS_PINNED"
		else
			echo "[refs] 基准提交在本地，但工作树当前指向 ${head:0:12}"
			echo "[refs] 警告：正文行号按 ${SPRING_REFS_PINNED:0:12} 记录，跨版本对照请以 commit 为准，不要以分支名为准"
		fi
		return 0
	fi
	echo "[refs] 警告：本地没有基准提交 ${SPRING_REFS_PINNED:0:12}"
	echo "[refs] 影响：正文与附录里的行号可能对不上；这不阻断构建，核对时请切到该 commit 或改用官方 Javadoc"
	return 0
}

case "${1:-status}" in
fetch) cmd_fetch "${2:-}" ;;
status) cmd_status ;;
verify) cmd_verify ;;
path) cmd_path ;;
-h | --help | help) usage ;;
*)
	echo "未知命令: $1" >&2
	usage >&2
	exit 2
	;;
esac
