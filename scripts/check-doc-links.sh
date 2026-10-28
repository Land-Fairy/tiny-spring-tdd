#!/usr/bin/env bash
# 检查所有 markdown 里的相对链接是否存在。
#
# 为什么需要它：文档腐败里最常见的一种是「文件改名了，链接没改」。它不会让任何测试变红，
# 只会让点链接的读者撞上 404。这个脚本 + CI 是这类问题的唯一防线。
#
# 用法：bash scripts/check-doc-links.sh        # 打印坏链并以非 0 退出
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2

broken=0
checked=0
while IFS= read -r file; do
	dir=$(dirname "$file")
	# 抓 ](target) 里的 target，去掉锚点与查询串
	while IFS= read -r target; do
		[ -z "$target" ] && continue
		case "$target" in
			http://*|https://*|mailto:*|\#*) continue ;;
		esac
		target=${target%%#*}
		[ -z "$target" ] && continue
		checked=$((checked + 1))
		if [ ! -e "$dir/$target" ]; then
			printf '坏链 %s -> %s\n' "$file" "$target"
			broken=$((broken + 1))
		fi
	done < <(grep -o '](\([^)]*\))' "$file" | sed 's/^](//; s/)$//')
done < <(git ls-files '*.md')

printf '检查 %d 条相对链接，坏链 %d 条\n' "$checked" "$broken"
[ "$broken" -eq 0 ]
