#!/usr/bin/env bash
#
# 第七道闸门：跨章回归不变式（propagation invariant）。
#
# 本仓 35 个章节模块是同一个代码库的 35 个快照：第 K 章修掉的东西，第 K+1 章必须有，
# 第 K+2 章必须还有。历史上有两次事故只有靠人工比对才发现：
#   1. 第 27 章的 .gitignore 把 aop/target 当构建产物忽略，18 个源文件从未入库；
#   2. 第 34 章的一次复制把旧版文档带回来，覆盖了已经修好的措辞。
#
# 机械判定用两条规矩，都能在「3 章窗口」内发现上面两类事故：
#
#   规则 1（不得回退）：若文件在 K 与 K+2 内容相同、而与 K+1 不同，
#       说明第 K+1 章的改动在下一章就丢了——这是「修复没传播」的指纹。
#   规则 2（不得静默删除）：若文件在 K 与 K+1 都存在，K+2 里却消失了，
#       说明某次复制漏了文件。
#
# 「章节自身的演进」不会误报：演进是 K → K+1 的**单向**变化，之后保持不变；
# 每条规则的窗口都是 3 章，两次真实变更也会被看见，所以误报需要「改了两章又改回去」，
# 这在正常开发里不会发生（真发生了，也值得停下来看看）。
#
# 用法：
#   bash scripts/check-propagation.sh          # 检查（有回退/丢失则退出码 1）
#   bash scripts/check-propagation.sh --stat   # 打印统计
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1

usage() {
	cat <<'USAGE'
用法：bash scripts/check-propagation.sh [--stat|--dry-run|--help]

  （无参数）  检查跨章回归不变式；有回退或缺失则退出码 1
  --stat      只打印统计，不逐条列
  --dry-run   只报告不判死：列全问题但仍然退出 0（本地探索 / 报告用）
  --help      显示本帮助

判据（两条互补，见 docs/CODE-STANDARDS.md「向后传播」）：
  REVERT  文件在 K 与 K+2 相同、与 K+1 不同 → 第 K+2 章的改动被后面覆盖丢了
  MISSING 相邻模块之间某个文件整体缺失 → 复制前进时漏了
USAGE
}

STAT_ONLY=0
REPORT_ONLY=0
case "${1:-}" in
	--help|-h) usage; exit 0 ;;
	--stat) STAT_ONLY=1 ;;
	--dry-run) REPORT_ONLY=1 ;;
	"") ;;
	*) echo "未知参数：$1（用 --help 看用法）" >&2; exit 2 ;;
esac

PATHS=("src/main/java" "src/test/java")
mapfile -t MODULES < <(printf '%s\n' step-*/ | sed 's#/##' | sort -V)
COUNT=${#MODULES[@]}

index_file() { # $1=module  ->  每行 "relpath<TAB>sha1"
	local mod="$1" path rel
	for path in "${PATHS[@]}"; do
		[[ -d "$mod/$path" ]] || continue
		while IFS= read -r -d '' f; do
			rel="${f#"$mod"/}"
			printf '%s\t%s\n' "$rel" "$(sha1sum "$f" | cut -d' ' -f1)"
		done < <(find "$mod/$path" -type f -name '*.java' -print0)
	done
}

module_index() { # $1=module -> 序号
	local i
	for i in "${!MODULES[@]}"; do
		[[ "${MODULES[i]}" == "$1" ]] && { printf '%s' "$i"; return 0; }
	done
	printf '%s' -1
}

declare -A CONTENT   # "relpath|module" -> sha1
declare -A PRESENT   # "relpath|module" -> 1

for mod in "${MODULES[@]}"; do
	while IFS=$'\t' read -r rel hash; do
		[[ -z "${rel:-}" ]] && continue
		CONTENT["$rel|$mod"]="$hash"
		PRESENT["$rel|$mod"]=1
	done < <(index_file "$mod")
done

reverts=0
deletions=0
files_checked=0

for key in "${!CONTENT[@]}"; do
	rel="${key%%|*}"
	mod="${key##*|}"
	# 只在「最早出现的那一章」判定一次
	first=""
	for m in "${MODULES[@]}"; do
		if [[ -n "${PRESENT["$rel|$m"]:-}" ]]; then first="$m"; break; fi
	done
	[[ "$mod" == "$first" ]] || continue
	((files_checked++))

	seq=()
	for m in "${MODULES[@]}"; do
		[[ -n "${PRESENT["$rel|$m"]:-}" ]] && seq+=("$m")
	done
	n=${#seq[@]}
	for ((i = 0; i + 2 < n; i++)); do
		k="${seq[i]}"; k1="${seq[i + 1]}"; k2="${seq[i + 2]}"
		h0="${CONTENT["$rel|$k"]}"
		h1="${CONTENT["$rel|$k1"]}"
		h2="${CONTENT["$rel|$k2"]}"
		if [[ "$h0" == "$h2" && "$h0" != "$h1" ]]; then
			((reverts++))
			[[ $STAT_ONLY -eq 1 ]] || printf 'REVERT   %s @ %s → %s → %s（改动在 %s 丢失）\n' "$rel" "$k" "$k1" "$k2" "$k2"
			break
		fi
	done
done

# 规则 2：框架代码在章节之间不得静默消失。
# 刻意排除 org/springframework/samples/**：那是「本章自己的例子」，按章不同是设计，
# 不是事故（第 21 章的样本本来就不该出现在第 22 章）。
reverts_files="${!PRESENT[@]}"
declare -A SEEN
for key in "${!PRESENT[@]}"; do
	rel="${key%%|*}"
	[[ "$rel" == *"/samples/"* ]] && continue
	SEEN["$rel"]=1
done

for rel in "${!SEEN[@]}"; do
	seq=()
	for m in "${MODULES[@]}"; do
		[[ -n "${PRESENT["$rel|$m"]:-}" ]] && seq+=("$m")
	done
	n=${#seq[@]}
	for ((i = 0; i + 1 < n; i++)); do
		idx_prev=$(module_index "${seq[i]}")
		idx_next=$(module_index "${seq[i + 1]}")
		if ((idx_next - idx_prev > 1)); then
			((deletions++))
			[[ $STAT_ONLY -eq 1 ]] || printf 'MISSING  %s 在 %s…%s 之间缺失（%s 与 %s 都有）\n' \
				"$rel" "${MODULES[idx_prev + 1]}" "${MODULES[idx_next - 1]}" "${seq[i]}" "${seq[i + 1]}"
		fi
	done
done

if [[ $STAT_ONLY -eq 1 ]]; then
	printf 'modules=%d files=%d reverts=%d missing=%d\n' "$COUNT" "$files_checked" "$reverts" "$deletions"
fi

if [[ $((reverts + deletions)) -gt 0 && $REPORT_ONLY -eq 1 ]]; then
	printf '\n[dry-run] 共 %d 处问题，仅报告不判死（去掉 --dry-run 即为 CI 判据）。\n' "$((reverts + deletions))"
	exit 0
fi

if [[ $((reverts + deletions)) -gt 0 ]]; then
	printf '\n跨章回归不变式失败：%d 处回退、%d 处缺失。\n' "$reverts" "$deletions" >&2
	printf '修法：把第 K 章的改动同样落到 K+1 与 K+2；若确实是章节演进，请在本文件顶部的注释里补一条说明，\n' >&2
	printf '并在 commit message 写清「哪一章引入、哪一章追平」。\n' >&2
	exit 1
fi

[[ $STAT_ONLY -eq 1 ]] || printf '跨章回归不变式通过：%d 个模块、%d 个文件。\n' "$COUNT" "$files_checked"
exit 0
