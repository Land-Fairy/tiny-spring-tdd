package org.springframework.util;

import java.util.regex.Pattern;

/**
 * 把<b>一段</b>（不含斜杠）的通配模式翻成正则。
 *
 * <p>
 * 为什么要翻成正则，而不是手写双指针回溯？两个理由：
 * <ol>
 * <li>一段里可能出现 {@code *a*b*c} 这种多星模式，手写回溯要写「贪心 + 回退」， 边界很容易错；交给 {@link Pattern}
 * 是经过验证的实现。</li>
 * <li>字面字符必须 {@link Pattern#quote(String)} 转义，否则 {@code a.b} 里的点会变成「任意字符」—— 这是通配匹配最经典的
 * bug。</li>
 * </ol>
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.util.AntPathStringMatcher}。 它还支持
 * {@code {name:regex}} 形式的 URI 模板变量（所以真实版本要多写 60 行来解析花括号）， 本书不做模板变量。
 */
class AntPathStringMatcher {

	private final Pattern pattern;

	AntPathStringMatcher(String pattern) {
		this.pattern = Pattern.compile(toRegex(pattern), Pattern.DOTALL);
	}

	/**
	 * {@code *} → {@code .*}、{@code ?} → {@code .}、其余字面量转义。
	 * @param glob 一段通配模式
	 * @return 正则表达式
	 */
	private static String toRegex(String glob) {
		StringBuilder regex = new StringBuilder(glob.length() * 2);
		for (int i = 0; i < glob.length(); i++) {
			char ch = glob.charAt(i);
			if (ch == '*') {
				regex.append(".*");
			}
			else if (ch == '?') {
				regex.append('.');
			}
			else {
				regex.append(Pattern.quote(String.valueOf(ch)));
			}
		}
		return regex.toString();
	}

	/**
	 * 整段匹配（不是 find）。
	 * @param str 待匹配的一段
	 * @return 匹配则为 {@code true}
	 */
	boolean match(String str) {
		return this.pattern.matcher(str).matches();
	}

}
