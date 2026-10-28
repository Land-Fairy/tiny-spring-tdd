package org.springframework.util;

/**
 * 极简通配符匹配：只支持 {@code *}（匹配任意长度，包括空）。
 * <p>
 * 为什么要单独一个类？因为「方法名匹配」和「路径匹配」（第 18 章的 {@code AntPathMatcher}）是两套语义： 路径里 {@code *}
 * 只配一段、{@code **} 配多段、还有 {@code ?} 和 {@code {var}}；而方法名匹配只有一个 {@code *}，语义是「正则里
 * {@code .*} 的位置」。把方法论的名字交给路径匹配器，是初学者常犯的错 （{@code book/*} 永远匹配不上 {@code book}）。
 * <p>
 * 真实 Spring 的同名类有 107 行，方法是 {@code simpleMatch}；我们这一版是它的核心递归。
 */
public final class PatternMatchUtils {

	private PatternMatchUtils() {
	}

	/**
	 * 用只含 {@code *} 的模式匹配字符串。
	 * @param pattern 模式（可能是 {@code null}）
	 * @param str 待匹配的字符串（可能是 {@code null}）
	 * @return 匹配时返回 {@code true}；任一为 {@code null} 时返回 {@code false}
	 */
	public static boolean simpleMatch(String pattern, String str) {
		if (pattern == null || str == null) {
			return false;
		}
		int firstIndex = pattern.indexOf('*');
		if (firstIndex == -1) {
			return pattern.equals(str);
		}
		if (firstIndex == 0) {
			if (pattern.length() == 1) {
				return true;
			}
			int nextIndex = pattern.indexOf('*', firstIndex + 1);
			if (nextIndex == -1) {
				return str.endsWith(pattern.substring(1));
			}
			String part = pattern.substring(1, nextIndex);
			if (part.isEmpty()) {
				return simpleMatch(pattern.substring(nextIndex), str);
			}
			int partIndex = str.indexOf(part);
			while (partIndex != -1) {
				if (simpleMatch(pattern.substring(nextIndex), str.substring(partIndex + part.length()))) {
					return true;
				}
				partIndex = str.indexOf(part, partIndex + 1);
			}
			return false;
		}
		return (str.length() >= firstIndex && pattern.regionMatches(0, str, 0, firstIndex)
				&& simpleMatch(pattern.substring(firstIndex), str.substring(firstIndex)));
	}

}
