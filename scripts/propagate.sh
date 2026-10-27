#!/usr/bin/env bash
#
# propagate.sh — 把「第 N 章」的修复向后传播到所有后续章节模块。
#
# 为什么需要它：本书每一章都是一个**完整可运行的副本**（step-NN-*），
# 后面章节的代码是从前面复制前进的。所以一旦在第 05 章修了一个 bug，
# 同样的代码还存在于 step-06、step-07……等 30 来个副本里。
# 手工同步等于抄 30 遍，而且一定会漏。
#
# 做法：把修复算成一个补丁，按章节顺序逐个 apply 到后面的模块。
# 冲突即停 —— 因为「后面改得更晚」意味着那个副本已经演进了，
# 需要人看一眼再决定，而不是让脚本猜。
#
# 用法示例：
#   ./scripts/propagate.sh --from step-05-property-injection --commit HEAD
#   ./scripts/propagate.sh --from step-05-property-injection --commit HEAD \
#       --paths src/main/java/org/springframework/beans/PropertyValue.java
#   ./scripts/propagate.sh --from step-05-property-injection --commit HEAD --dry-run
#   ./scripts/propagate.sh --from step-05-property-injection --commit HEAD --verify
#
# 退出码：
#   0  全部成功（或无事可做）
#   1  用法错误 / 环境不对
#   2  某个模块冲突，已停在冲突处（更早的模块已改好）
#   3  --verify 时某个模块测试失败

set -u -o pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "${REPO_ROOT}" || exit 1

FROM=""
COMMIT=""
PATHS=""
UNTIL=""
DRY_RUN=0
VERIFY=0
FORCE=0

die() {
	echo "[propagate] 错误：$*" >&2
	exit 1
}

usage() {
	sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
	case "$1" in
		--from) FROM="${2:-}"; shift 2 ;;
		--from=*) FROM="${1#*=}"; shift ;;
		--commit) COMMIT="${2:-}"; shift 2 ;;
		--commit=*) COMMIT="${1#*=}"; shift ;;
		--paths) PATHS="${2:-}"; shift 2 ;;
		--paths=*) PATHS="${1#*=}"; shift ;;
		--until) UNTIL="${2:-}"; shift 2 ;;
		--until=*) UNTIL="${1#*=}"; shift ;;
		--dry-run) DRY_RUN=1; shift ;;
		--verify) VERIFY=1; shift ;;
		--force) FORCE=1; shift ;;
		-h|--help) usage; exit 0 ;;
		*) die "未知参数 '$1'（试 --help）" ;;
	esac
done

[[ -n "${FROM}" ]] || die "必须给 --from <起始模块，如 step-05-property-injection>"
[[ -n "${COMMIT}" ]] || die "必须给 --commit <提交，如 HEAD 或 sha>"
[[ -d "${FROM}" ]] || die "找不到模块目录 '${FROM}'"
git rev-parse --verify "${COMMIT}^{commit}" >/dev/null 2>&1 || die "'${COMMIT}' 不是本仓库的提交"

# 章节模块按名字排序就是章节顺序（step-01…step-35）。
mapfile -t MODULES < <(find . -maxdepth 1 -type d -name 'step-*' -printf '%f\n' | sort)
[[ ${#MODULES[@]} -gt 0 ]] || die "当前目录下没有 step-* 模块"

# 找到起始位置；找不到说明 --from 不是章节模块。
FROM_INDEX=-1
for i in "${!MODULES[@]}"; do
	if [[ "${MODULES[$i]}" == "${FROM}" ]]; then
		FROM_INDEX=$i
		break
	fi
done
(( FROM_INDEX >= 0 )) || die "'${FROM}' 不在模块列表里：${MODULES[*]}"

# 目标模块 = 起始模块之后的那些（不含起始模块自己，它已经改好了）。
TARGETS=()
for i in $(seq $((FROM_INDEX + 1)) $((${#MODULES[@]} - 1))); do
	TARGETS+=("${MODULES[$i]}")
	if [[ -n "${UNTIL}" && "${MODULES[$i]}" == "${UNTIL}" ]]; then
		break
	fi
done

if [[ ${#TARGETS[@]} -eq 0 ]]; then
	echo "[propagate] ${FROM} 已是最新模块，没有需要传播的目标。"
	exit 0
fi

# 要传播的路径：默认取该提交里改到起始模块下的所有文件。
if [[ -z "${PATHS}" ]]; then
	PATHS=$(git diff --name-only "${COMMIT}^" "${COMMIT}" -- "${FROM}/" \
		| sed "s|^${FROM}/||" | paste -sd, -)
	[[ -n "${PATHS}" ]] || die "提交 ${COMMIT} 没有改到 ${FROM}/ 下的任何文件"
fi

IFS=',' read -r -a PATH_LIST <<< "${PATHS}"

if (( ! FORCE )); then
	dirty=$(git status --porcelain -- "${TARGETS[@]}" 2>/dev/null)
	if [[ -n "${dirty}" ]]; then
		echo "[propagate] 目标模块里有未提交的改动：" >&2
		echo "${dirty}" | sed 's/^/    /' >&2
		die "先提交或还原它们（或加 --force 强行继续）"
	fi
fi

echo "[propagate] 起点   ${FROM}"
echo "[propagate] 提交   $(git rev-parse --short "${COMMIT}")  $(git log -1 --format=%s "${COMMIT}")"
echo "[propagate] 目标   ${TARGETS[*]}"
echo "[propagate] 文件   ${PATH_LIST[*]}"
echo

PATCH_DIR=$(mktemp -d)
trap 'rm -rf "${PATCH_DIR}"' EXIT

failures=0
for module in "${TARGETS[@]}"; do
	touched=()
	skipped=()
	conflict=""

	for path in "${PATH_LIST[@]}"; do
		[[ -n "${path}" ]] || continue

		# 「已经一样」= 这个模块早就有了这个修复，不用 apply。
		if git cat-file -e "${COMMIT}:${FROM}/${path}" 2>/dev/null \
			&& [[ -f "${module}/${path}" ]] \
			&& git show "${COMMIT}:${FROM}/${path}" | cmp -s - "${module}/${path}"; then
			skipped+=("${path}")
			continue
		fi

		patch="${PATCH_DIR}/$(echo "${module}/${path}" | tr '/' '_').patch"
		git diff "${COMMIT}^" "${COMMIT}" -- "${FROM}/${path}" > "${patch}"
		[[ -s "${patch}" ]] || die "生成补丁为空：${FROM}/${path}"

		# -p2 去掉 a/ 与 <起始模块>/ 两段；--directory 再把目标模块名补回去。
		# 注意：不能用 `git apply --check --3way` 预检 —— 它在「三方合并会冲突」时
		# 依然返回成功（实测），只有真正 apply 之后才能从索引里看出冲突。
		git apply --3way -p2 --directory="${module}" "${patch}" 2>/dev/null
		apply_rc=$?
		unmerged=$(git diff --name-only --diff-filter=U -- "${module}")
		if (( apply_rc != 0 )) || [[ -n "${unmerged}" ]]; then
			# 冲突位置：冲突标记所在行号 + 补丁自己说的失败位置。
			positions=""
			while IFS= read -r bad; do
				[[ -n "${bad}" ]] || continue
				lines=$(grep -n '^<<<<<<<' "${bad}" 2>/dev/null | cut -d: -f1 | paste -sd, -)
				positions="${positions}    ${bad}  冲突标记在行 ${lines:-（未知）}
"
			done <<< "${unmerged}"
			# 把这次尝试的痕迹擦掉，保证「冲突即停」不留半个改动。
			if [[ -n "${unmerged}" ]]; then
				while IFS= read -r bad; do
					[[ -n "${bad}" ]] && git reset -q HEAD -- "${bad}" && git checkout -q -- "${bad}"
				done <<< "${unmerged}"
			fi
			conflict="${path}
${positions:-    （补丁无法应用，git 未给出冲突文件）
}    git apply 输出：$(git apply --check -p2 --directory="${module}" "${patch}" 2>&1 | head -3)"
			break
		fi
		touched+=("${path}")
	done

	if [[ -n "${conflict}" ]]; then
		echo "[propagate] 冲突 ${module}"
		echo "${conflict}" | sed 's/^/    /'
		echo
		echo "[propagate] 已停止：${module} 之前的目标都已改好，${module} 本身没有任何改动。"
		echo "[propagate] 下一步：手工把这个补丁应用一次，再把该修复并在提交里。"
		exit 2
	fi

	if [[ ${#touched[@]} -eq 0 ]]; then
		echo "[propagate] 跳过 ${module}（${#skipped[@]} 个文件已一致）"
		continue
	fi

	echo "[propagate] 应用 ${module}（${#touched[@]} 个文件）"
	printf '    %s\n' "${touched[@]}"

	if (( VERIFY )); then
		if (( DRY_RUN )); then
			echo "    [dry-run] 跳过测试"
		elif ./mvnw -q -pl "${module}" test >"${PATCH_DIR}/verify.log" 2>&1; then
			echo "    测试通过"
		else
			echo "    测试失败，日志："
			tail -30 "${PATCH_DIR}/verify.log" | sed 's/^/    /'
			exit 3
		fi
	fi
done

echo
if (( DRY_RUN )); then
	echo "[propagate] dry-run：以上改动没有写入（补丁只做了 --check）。"
	echo "[propagate] 重新执行时去掉 --dry-run。"
else
	echo "[propagate] 完成。改动未提交，确认后自己 commit。"
fi
exit 0
