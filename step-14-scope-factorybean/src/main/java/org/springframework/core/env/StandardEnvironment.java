package org.springframework.core.env;

import org.springframework.util.PropertyPlaceholderHelper;

/**
 * 标准环境实现：按来源链顺序查值，并用 {@link PropertyPlaceholderHelper} 解析 {@code ${...}}。
 *
 * <p>
 * <b>两个字段，两种严格性</b>：{@code nonStrictHelper} 解析不了时保留原文，{@code strictHelper} 解析不了时抛异常。
 * 同一个类里为什么要两套？因为「解析」和「校验」是两种语义： {@code resolvePlaceholders} 常用于「能解析就解析」的宽松场景（比如拼日志），
 * {@code resolveRequiredPlaceholders} 用于「配错了必须立刻失败」的场景（比如占位符配置器）。
 * 把严格性做成两个对象，比在每个调用点传布尔参数更难用错。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.env.StandardEnvironment}（103 行，只负责加
 * {@code systemProperties} / {@code systemEnvironment} 两个来源）与
 * {@code AbstractEnvironment}（601 行，真正的 {@code getProperty} / {@code resolvePlaceholders}
 * 都在它的父类 {@code AbstractPropertyResolver} 里）。本书把它们合成一个类。
 *
 * @see Environment
 */
public class StandardEnvironment implements Environment {

	private final MutablePropertySources propertySources = new MutablePropertySources();

	private final PropertyPlaceholderHelper nonStrictHelper = new PropertyPlaceholderHelper("${", "}", ":", true);

	private final PropertyPlaceholderHelper strictHelper = new PropertyPlaceholderHelper("${", "}", ":", false);

	@Override
	public MutablePropertySources getPropertySources() {
		return this.propertySources;
	}

	@Override
	public boolean containsProperty(String key) {
		return getProperty(key) != null;
	}

	@Override
	public String getProperty(String key) {
		return getProperty(key, null);
	}

	@Override
	public String getProperty(String key, String defaultValue) {
		// 沿链顺序问一遍：前面的来源赢，这就是「优先级」的全部实现
		for (PropertySource<?> propertySource : this.propertySources) {
			Object value = propertySource.getProperty(key);
			if (value != null) {
				return value.toString();
			}
		}
		return defaultValue;
	}

	@Override
	public String getRequiredProperty(String key) throws IllegalStateException {
		String value = getProperty(key);
		if (value == null) {
			throw new IllegalStateException("Required key '" + key + "' not found");
		}
		return value;
	}

	@Override
	public String resolvePlaceholders(String text) {
		return this.nonStrictHelper.replacePlaceholders(text, this::getProperty);
	}

	@Override
	public String resolveRequiredPlaceholders(String text) throws IllegalArgumentException {
		return this.strictHelper.replacePlaceholders(text, this::getProperty);
	}

}
