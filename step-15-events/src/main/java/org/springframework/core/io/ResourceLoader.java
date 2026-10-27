package org.springframework.core.io;

/**
 * 把「一个字符串位置」变成 {@link Resource}。
 *
 * <p>
 * 这一层是给别人用的入口：XML 解析器、扫描器都接收一个位置字符串，然后靠它拿到资源， 而不需要自己判断「这是 classpath 还是文件」。
 *
 * @see org.springframework.core.io.ResourceLoader 真实对应物
 */
public interface ResourceLoader {

	/** classpath 协议前缀。 */
	String CLASSPATH_URL_PREFIX = "classpath:";

	/**
	 * 解析位置字符串。
	 * @param location 形如
	 * {@code classpath:ch07/app.properties}、{@code file:/tmp/a}、{@code https://...}
	 * @return 资源对象（不一定存在，是否存在由 {@link Resource#exists()} 回答）
	 */
	Resource getResource(String location);

}
