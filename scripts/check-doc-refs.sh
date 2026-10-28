#!/usr/bin/env bash
# 第 8 道闸门：文档引用的可验证性（gate 8: reference resolvability）
#
# 为什么需要它：tiny-spring-tdd 的教材价值建立在「注释与文档里的每一个引用都是真的」上。
# Checkstyle / PMD / JaCoCo / ArchUnit 检查的都是代码本身，看不见旁证——
# 第 01 章评审已发现 5 处引用指向不存在的东西（虚构类名、编造的方法名、编造的行号）。
#
# 本脚本只做一件事：把 javadoc 与章节文档里的每一处引用，落到真实存在的文件与行上。
#
# 判定语义：一个引用必须能在下面三处之一解析到，否则报 ERROR
#   ① 本项目（step-* 全部模块，含内部类）  ② 上游 Spring 源码树（含内部类）  ③ JDK
#
# 用法
#   scripts/check-doc-refs.sh --module step-01-map-container [--doc docs/chapters/01-map-container.md]
#   scripts/check-doc-refs.sh --all
# 环境变量
#   ROOT=仓库根（默认本脚本所在仓库）  SPRING_REF=上游源码根（默认 /home/jasen/refs/spring-framework-6.2.x）
#   上游源码树不存在时，凡标注「出处：」的上游引用一律跳过并打 INFO，不误判成本地越界。
# 退出码
#   0 = 全部可解析   1 = 存在无法解析的引用   2 = 用法错误
#
# 两类白名单（都必须写理由，见文件头）：
#   scripts/doc-refs-allow-symbols.txt  —— 不是本项目类的简单类名（JDK、上游类型等）
#   scripts/doc-refs-allow-missing.txt  —— 文中明示为「临时探针 / 已拆分的旧版」的文件名

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${ROOT:-$(cd "$HERE/.." && pwd)}"
SPRING_REF="${SPRING_REF:-/home/jasen/refs/spring-framework-6.2.x}"
ALLOWLIST="$HERE/doc-refs-allow-symbols.txt"
ALLOW_MISSING="$HERE/doc-refs-allow-missing.txt"
SKIP_UPSTREAM=0
[ -d "$SPRING_REF" ] || SKIP_UPSTREAM=1
CACHE="${TMPDIR:-/tmp}/gate8-$$"
mkdir -p "$CACHE"
trap 'rm -rf "$CACHE"' EXIT

errors=0
warns=0
err() { printf 'ERROR %s\n' "$*"; errors=$((errors + 1)); }
warn() { printf 'WARN  %s\n' "$*"; warns=$((warns + 1)); }
note() { printf 'INFO  %s\n' "$*"; }

# 只放「文中明示不是仓库文件」的名字：临时探针、已拆分的旧版。
# 其它一律不许进这张表——进表就等于承认这个引用不可验证。
allow_missing() {
	[ -f "$ALLOW_MISSING" ] || return 1
	# 注意不能写成 '$1 == n { exit 0 }'：exit 会触发 END，END 再 exit 1 就把命中吃掉了。
	awk -F'\t' -v n="$1" '$1 == n { found = 1 } END { exit(found ? 0 : 1) }' "$ALLOW_MISSING"
}

# ═══════════════════ 索引（每轮只建一次） ═══════════════════

local_files() {
	local f="$CACHE/local_files"
	if [ ! -f "$f" ]; then
		find "$ROOT" -maxdepth 12 -name '*.java' -not -path '*/target/*' -printf '%f\t%p\n' 2>/dev/null |
			sort -u >"$f"
	fi
	printf '%s' "$f"
}

local_types() {
	local f="$CACHE/local_types"
	if [ ! -f "$f" ]; then
		local_files >/dev/null
		cut -f2 "$(local_files)" | tr '\n' '\0' |
			xargs -0 grep -hoE '\b(class|interface|enum|record|@interface) +[A-Z][A-Za-z0-9_]*' 2>/dev/null |
			awk '{print $2}' | sort -u >"$f"
	fi
	printf '%s' "$f"
}

upstream_files() {
	local f="$CACHE/up_files"
	if [ ! -f "$f" ]; then
		find "$SPRING_REF" -name '*.java' -not -path '*/build/*' -printf '%f\t%p\n' 2>/dev/null |
			sort -u >"$f"
	fi
	printf '%s' "$f"
}

upstream_types() {
	local f="$CACHE/up_types"
	if [ ! -f "$f" ]; then
		if [ -d "$SPRING_REF" ]; then
			find "$SPRING_REF" -name '*.java' -not -path '*/build/*' -print0 2>/dev/null |
				xargs -0 grep -hoE '\b(class|interface|enum|record|@interface) +[A-Z][A-Za-z0-9_]*' 2>/dev/null |
				awk '{print $2}' | sort -u >"$f"
		else
			: >"$f"
		fi
	fi
	printf '%s' "$f"
}

jdk_types() {
	local f="$CACHE/jdk_types"
	if [ ! -f "$f" ]; then
		if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jimage" ]; then
			"$JAVA_HOME/bin/jimage" list "$JAVA_HOME/lib/modules" 2>/dev/null |
				grep -oE '[A-Za-z][A-Za-z0-9_$]*\.class$' | sed 's/\.class$//; s/\$.*//' | sort -u >"$f"
		else
			: >"$f"
		fi
	fi
	printf '%s' "$f"
}

# ═══════════════════ 解析 ═══════════════════

# 三处（本项目 / 上游 / JDK）合成一个排序去重的集合，R1 一次性加载进 awk。
# 之前每个 {@code X} 都 spawn 三次 grep，35 章跑 9 分半；合并后单进程扫描。
resolvable_set() {
	local f="$CACHE/resolvable"
	if [ ! -f "$f" ]; then
		{
			cat "$(local_types)" "$(upstream_types)" "$(jdk_types)"
			[ -f "$ALLOWLIST" ] && cat "$ALLOWLIST"
		} 2>/dev/null | sort -u >"$f"
	fi
	printf '%s' "$f"
}

in_list() { grep -qxF "$1" "$2"; }

resolves() {
	local n="$1"
	in_list "$n" "$(local_types)" && return 0
	in_list "$n" "$(upstream_types)" && return 0
	in_list "$n" "$(jdk_types)" && return 0
	if [ -f "$ALLOWLIST" ] && in_list "$n" "$ALLOWLIST"; then return 0; fi
	return 1
}

resolve_path() {
	local name="$1" module="${2:-}" hit
	if [ -n "$module" ]; then
		hit=$(find "$module" -name "$name" -not -path '*/target/*' 2>/dev/null | head -1)
		if [ -n "$hit" ]; then printf '%s' "$hit"; return 0; fi
	fi
	hit=$(awk -F'\t' -v n="$name" '$1 == n { print $2; exit }' "$(local_files)")
	if [ -n "$hit" ]; then printf '%s' "$hit"; return 0; fi
	hit=$(awk -F'\t' -v n="$name" '$1 == n { print $2; exit }' "$(upstream_files)")
	if [ -n "$hit" ]; then printf '%s' "$hit"; return 0; fi
	return 1
}

resolve_upstream_path() {
	awk -F'\t' -v n="$1" '$1 == n { print $2; exit }' "$(upstream_files)"
}

cites_upstream() {
	# 纯 bash 判断，不起子进程：pipefail 下 `printf | grep -q` 会因 SIGPIPE 偶发假阴性，
	# 把「标注了出处」的行误判成「本地引用」，进而把上游行号报成本地行号越界。
	case "$1" in
		*出处* | *spring-beans* | *spring-core* | *spring-context* | *spring-aop* | \
			*spring-expression* | *spring-jcl* | *6.2*) return 0 ;;
		*) return 1 ;;
	esac
}

# ═══════════════════ R1: javadoc 里的 {@code X} / {@link X} ═══════════════════

# 只检查「像类名」的词：以大写开头、且以已知类型后缀结尾（后缀表见下面 awk 里的正则）。

check_javadoc_refs() {
	local module="$1"
	while IFS=$'\t' read -r f l s; do
		if [ "$SKIP_UPSTREAM" -eq 1 ]; then
			# 没有上游源码树时，绝大多数 {@code X} 本来就只能在上游找到——
			# 此时「查不到」不是错，而是「没法核对」。降级为 WARN，并且只打印前 5 条，
			# 否则一次 CI 会刷出上千行，真正的问题反而被淹没。
			warns=$((warns + 1))
			if [ "$warns" -le 5 ]; then
				printf 'WARN  无法核对 javadoc 引用 {%s}（%s:%s；无上游源码树，本地与 JDK 都没有）\n' \
					"$s" "${f#"$ROOT"/}" "$l"
			fi
			continue
		fi
		err "${f#"$ROOT"/}:${l}: javadoc 引用的 {${s}} 在本项目/上游/JDK 都不存在"
	done < <(find "$module" -name '*.java' -not -path '*/target/*' -print0 |
		xargs -0 awk -v resolvable="$(resolvable_set)" '
			BEGIN { while ((getline line < resolvable) > 0) ok[line] = 1 }
			{
				rest = $0
				while (match(rest, /\{@(code|link) +[A-Z][A-Za-z0-9_$]*/)) {
					tok = substr(rest, RSTART, RLENGTH)
					sub(/^\{@(code|link) +/, "", tok)
					rest = substr(rest, RSTART + RLENGTH)
					if (tok !~ /(Tests|Test|Exception|Error|Container|Factory|FactoryBean|Registry|Bean|BeanDefinition|PostProcessor|Processor|Advisor|Interceptor|Resolver|Handler|Context|Configurer|Enhancer|Creator|Source|Reader|Writer|Aware|Capable)$/) continue
					if (tok in ok) continue
					printf "%s\t%d\t%s\n", FILENAME, FNR, tok
				}
			}')
}

# ═══════════════════ R2/R3/R4: 章节文档里的 文件.java:行 ═══════════════════

check_doc_refs() {
	local module="$1" doc="$2" line_no=0 line ref file start end total path method up
	while IFS= read -r line; do
		line_no=$((line_no + 1))
		up=0
		cites_upstream "$line" && up=1

		# R3: 栈帧形态 MethodName(File.java:LINE)，方法名必须真的存在于该文件
		while IFS= read -r ref; do
			[ -n "$ref" ] || continue
			method=${ref%%(*}
			method=${method##*.}
			file=$(printf '%s' "$ref" | sed -E 's/.*\(([A-Za-z0-9_$]+\.java):.*/\1/')
			case "$method" in '' | *[!A-Za-z0-9_$]*) continue ;; esac
			# lambda$method$0 是 JVM 自己生成的栈帧名，源码里当然找不到，不是编的
			case "$method" in lambda\$*) continue ;; esac
			if in_list "${file%.java}" "$(jdk_types)"; then continue; fi
			path=$(resolve_path "$file" "$module")
			if [ -z "$path" ]; then
				if allow_missing "$file"; then
					note "$doc:$line_no: $file 在 doc-refs-allow-missing.txt 里（文中已声明不是仓库文件）"
				else
					err "$doc:$line_no: 栈帧引用的文件不存在: $file"
				fi
				continue
			fi
			# 只认代码里的方法声明：先剥注释，注释里可以写任何东西。
			# 这里必须用 grep -c 而不是 grep -q：脚本开了 pipefail，grep -q 提前退出会让上游
			# sed 收到 SIGPIPE(141)，管道被判定为失败——大小文件表现不同，同一个栈帧时红时绿。
			local hits
			hits=$(sed -e 's://.*::' -e '/^[[:space:]]*\*/d' -e '/^[[:space:]]*\/\*/d' "$path" |
				grep -cE "^[^/]*[^A-Za-z0-9_]${method}[[:space:]]*\(" 2>/dev/null)
			if [ "${hits:-0}" -eq 0 ]; then
				err "$doc:$line_no: $file 里没有方法 ${method}() —— 栈帧是编的"
			fi
		done < <(grep -oE '[A-Za-z0-9_$]+\([A-Za-z0-9_$]+\.java:[0-9]+\)' <<<"$line")

		# R2: 普通引用 File.java:LINE 或 File.java:起-止
		while IFS= read -r ref; do
			[ -n "$ref" ] || continue
			file=$(printf '%s' "$ref" | sed -E 's/^([A-Za-z0-9_$]+\.java).*/\1/')
			start=$(printf '%s' "$ref" | sed -E 's/^[A-Za-z0-9_$]+\.java:([0-9]+).*/\1/')
			end=$(printf '%s' "$ref" | sed -nE 's/^[A-Za-z0-9_$]+\.java:[0-9]+-([0-9]+)$/\1/p')
			[ -n "$end" ] || end="$start"
			if in_list "${file%.java}" "$(jdk_types)"; then continue; fi
			# 附录/README/ADR 这类文档讲的是真实 Spring：行内没写出处也先按上游解析，
			# 上游没有这个类才回到本项目。章节正文不受影响（UPSTREAM_FIRST 只在附录模式置 1）。
			if [ "$up" -eq 0 ] && [ "${UPSTREAM_FIRST:-0}" -eq 1 ] && [ -n "$(resolve_upstream_path "$file")" ]; then
				up=1
			fi

			if [ "$up" -eq 1 ] && [ "$SKIP_UPSTREAM" -eq 1 ]; then
				note "$doc:$line_no: 无上游源码树（SPRING_REF），跳过上游引用 $file"
				continue
			fi
			if [ "$up" -eq 1 ]; then
				path=$(resolve_upstream_path "$file")
				if [ -z "$path" ]; then
					err "$doc:$line_no: 标注为上游出处的 $file 在上游源码树里不存在"
					continue
				fi
			else
				path=$(resolve_path "$file" "$module")
				if [ -z "$path" ]; then
					if allow_missing "$file"; then
						note "$doc:$line_no: $file 在 doc-refs-allow-missing.txt 里（文中已声明不是仓库文件）"
					elif [ "$SKIP_UPSTREAM" -eq 1 ]; then
						# 这类文件（AbstractBeanFactory、InjectionMetadata……）本来就只在上游存在。
						# 没有上游源码树时无法核对，降级为 WARN；有上游却找不到才是真错。
						warns=$((warns + 1))
						if [ "$warns" -le 5 ]; then
							printf 'WARN  无法核对 %s（%s:%s；无上游源码树，本项目也没有这个文件）\n' \
								"$file" "$doc" "$line_no"
						fi
					else
						err "$doc:$line_no: 引用的 $file 在本项目与上游都找不到"
					fi
					continue
				fi
			fi

			total=$(wc -l <"$path")
			if [ "$end" -gt "$total" ]; then
				# 本地文件比引用的行号短：很可能这一行讲的是上游 Spring 的同一份文件。
				# 回退到上游再判一次，避免把「上游行号」误报成「编的行号」。
				local upath utotal
				upath=$(resolve_upstream_path "$file")
				utotal=0
				[ -n "$upath" ] && utotal=$(wc -l <"$upath")
				if [ "$utotal" -gt 0 ] && [ "$end" -le "$utotal" ]; then
					warn "$doc:$line_no: $file 本地只有 $total 行，第 $end 行按上游解读（上游 $utotal 行）；建议行内标注「出处：」"
					continue
				fi
				if [ "$SKIP_UPSTREAM" -eq 1 ]; then
					# 没有上游源码树就没法区分「本地行号漂移」和「上游行号」——报 WARN，不冒充确定。
					warn "$doc:$line_no: $file 本地只有 $total 行，却引用到第 $end 行；无上游源码树，无法判断是哪一种"
					continue
				fi
				err "$doc:$line_no: $file 只有 $total 行，引用到第 $end 行（${path#"$ROOT"/}）"
				continue
			fi
			if [ "$up" -eq 0 ] && [ -z "$(sed -n "${start}p" "$path" | tr -d '[:space:]')" ]; then
				warn "$doc:$line_no: $file 第 ${start} 行是空行（行号可能是编的）"
			fi
		done < <(grep -oE '[A-Za-z0-9_$]+\.java:[0-9]+(-[0-9]+)?' <<<"$line")
	done <"$doc"
}

# ═══════════════════ 主流程 ═══════════════════

run_one() {
	local module="$1" doc="${2:-}"
	echo "── ${module:-$(basename "$doc")}"
	[ -n "$module" ] && check_javadoc_refs "$module"
	if [ -n "$doc" ]; then
		if [ -f "$doc" ]; then
			check_doc_refs "$module" "$doc"
		else
			err "文档不存在: $doc"
		fi
	fi
}

main() {
	local module="" doc="" all=0 n m d
	while [ $# -gt 0 ]; do
		case "$1" in
			--module) module="${2:-}"; shift 2 ;;
			--doc) doc="${2:-}"; shift 2 ;;
			--all) all=1; shift ;;
			*) echo "未知参数: $1" >&2; exit 2 ;;
		esac
	done

	if [ "$all" -eq 1 ]; then
		for d in "$ROOT"/step-*/; do
			[ -d "$d" ] || continue
			n=$(basename "$d" | sed -E 's/^step-([0-9]+)-.*/\1/')
			m=$(ls "$ROOT"/docs/chapters/"$n"-*.md 2>/dev/null | head -1)
			run_one "${d%/}" "${m:-}"
		done
		for e in "$ROOT"/docs/appendix/*.md "$ROOT"/README.md "$ROOT"/GLOSSARY.md \
			"$ROOT"/docs/CODE-STANDARDS.md "$ROOT"/docs/architecture-gap.md \
			"$ROOT"/docs/adr/*.md "$ROOT"/docs/spec/*.md; do
			[ -f "$e" ] || continue
			UPSTREAM_FIRST=1 run_one "" "$e"
		done
	elif [ -n "$module" ]; then
		run_one "$module" "$doc"
	else
		echo "用法: $0 --module DIR [--doc FILE] | --all" >&2
		exit 2
	fi

	echo
	if [ "$SKIP_UPSTREAM" -eq 1 ]; then
		echo "── 模式: 降级（无上游源码树 SPRING_REF=$SPRING_REF）——上游类名与上游行号一律记 WARN，未做核对"
	fi
	echo "── 汇总: ${errors} 个无法解析的引用, ${warns} 个警告"
	[ "$errors" -eq 0 ]
}

main "$@"
