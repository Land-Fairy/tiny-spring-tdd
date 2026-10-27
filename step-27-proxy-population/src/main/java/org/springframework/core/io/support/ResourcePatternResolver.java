package org.springframework.core.io.support;

import java.io.IOException;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * 比 {@link ResourceLoader} 多一件事：<b>一条路径可以变成一堆资源</b>。
 *
 * <p>
 * 关键在 {@link #CLASSPATH_ALL_URL_PREFIX} 结尾那个星号：{@code classpath:} 只取<b>第一个</b>命中的
 * classpath 根，{@code classpath*:} 取<b>全部</b>（多个 jar、多个目录都算）。 jar
 * 里为什么会有「多个根」？因为同一个包名可能被两个依赖 jar 各提供一份。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.io.support.ResourcePatternResolver}
 * （83 行，多一个 {@code getResourceLoader()} 与「按 ant 匹配取资源」的文档约定）。
 */
public interface ResourcePatternResolver extends ResourceLoader {

	/**
	 * 「所有 classpath 根都要找」的前缀。
	 */
	String CLASSPATH_ALL_URL_PREFIX = "classpath*:";

	/**
	 * 把一条（可能带通配符的）路径解析成多个资源。找不到时返回<b>空数组</b>，不返回 {@code null}。
	 * @param locationPattern 位置模式，例如 {@code classpath*:com/acme/**}{@code /*.class}
	 * @return 资源数组
	 * @throws IOException 读取文件系统或 jar 失败
	 */
	Resource[] getResources(String locationPattern) throws IOException;

}
