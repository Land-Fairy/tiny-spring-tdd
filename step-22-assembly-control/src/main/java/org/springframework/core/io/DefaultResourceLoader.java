package org.springframework.core.io;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * 位置字符串 → 资源的默认规则。
 *
 * <p>
 * 规则只有三条，顺序很重要：
 * <ol>
 * <li>带 {@code classpath:} 前缀 → classpath
 * <li>能被解析成 URL（有协议，如 {@code file:} / {@code http:}）→ URL
 * <li>其余一律当 classpath 路径
 * </ol>
 *
 * @see org.springframework.core.io.DefaultResourceLoader 真实对应物
 */
public class DefaultResourceLoader implements ResourceLoader {

	private final ClassLoader classLoader;

	public DefaultResourceLoader() {
		this(null);
	}

	public DefaultResourceLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	@Override
	public Resource getResource(String location) {
		if (location.startsWith(CLASSPATH_URL_PREFIX)) {
			return new ClassPathResource(location.substring(CLASSPATH_URL_PREFIX.length()), this.classLoader);
		}
		try {
			// 关键：URL 的构造器就是「这里有没有协议」的判断器。它抛异常说明没有协议。
			URL url = new URL(location);
			return new UrlResource(url);
		}
		catch (MalformedURLException ex) {
			// 没有协议 → 当 classpath 路径。真实 Spring 这里是 getResourceByPath(location)。
			return new ClassPathResource(location, this.classLoader);
		}
	}

}
