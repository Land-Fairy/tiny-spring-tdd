package org.springframework.core.env;

import java.util.Map;

/**
 * 以 {@link Map} 为底层数据的属性来源。
 *
 * <p>
 * 这是最简单、也是最常用的实现：测试里手工塞几个值、或者从 {@code .properties} 读进一张表，都用它。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.env.MapPropertySource}（63 行）。
 * 它还额外检查「map 里是否有非 String 的 key」（有则记一条 debug 日志），因为属性名的语义就是字符串。
 *
 * @see PropertySource
 * @see org.springframework.core.env.MapPropertySource
 */
public class MapPropertySource extends PropertySource<Map<String, Object>> {

	/**
	 * 用一个 Map 构造来源。
	 * @param name 来源名
	 * @param source 属性表
	 */
	public MapPropertySource(String name, Map<String, Object> source) {
		super(name, source);
	}

	@Override
	public Object getProperty(String name) {
		return getSource().get(name);
	}

}
