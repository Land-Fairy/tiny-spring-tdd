package org.springframework.util;

/**
 * 「一个路径模式能不能匹配一个路径」。
 *
 * <p>
 * 为什么要有这个接口？因为「怎么匹配」是策略：ant 风格（{@code **} 跨层）是一种， 正则是一种，前缀/后缀匹配又是一种。资源解析器只依赖这个接口，就能换匹配算法。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.util.PathMatcher}（126 行， 比本书多了
 * {@code extractPathWithinPattern}、{@code extractUriTemplateVariables}、
 * {@code getPatternComparator}、{@code combine} 等「给 MVC 用的」方法）。
 *
 * @see org.springframework.util.PathMatcher
 */
public interface PathMatcher {

	/**
	 * 这个字符串里有没有通配符。
	 * @param path 待检查的字符串
	 * @return 含 {@code *} 或 {@code ?} 则为 {@code true}
	 */
	boolean isPattern(String path);

	/**
	 * 整串匹配。
	 * @param pattern 模式
	 * @param path 待匹配路径
	 * @return 完全匹配则为 {@code true}
	 */
	boolean match(String pattern, String path);

}
