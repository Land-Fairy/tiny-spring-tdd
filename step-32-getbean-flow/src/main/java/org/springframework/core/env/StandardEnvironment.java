package org.springframework.core.env;

import java.util.LinkedHashSet;
import java.util.Set;

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
public class StandardEnvironment implements ConfigurableEnvironment {

	private final MutablePropertySources propertySources = new MutablePropertySources();

	private final PropertyPlaceholderHelper nonStrictHelper = new PropertyPlaceholderHelper("${", "}", ":", true);

	private final PropertyPlaceholderHelper strictHelper = new PropertyPlaceholderHelper("${", "}", ":", false);

	/**
	 * 激活的 profile。<b>用 {@link LinkedHashSet}</b>：profile 是集合语义（重复设置无意义）， 但
	 * {@code getActiveProfiles()} 要按设置顺序返回，否则日志与测试断言都不稳定。
	 */
	private final Set<String> activeProfiles = new LinkedHashSet<>();

	@Override
	public MutablePropertySources getPropertySources() {
		return this.propertySources;
	}

	@Override
	public void setActiveProfiles(String... profiles) {
		this.activeProfiles.clear();
		for (String profile : profiles) {
			addActiveProfile(profile);
		}
	}

	@Override
	public void addActiveProfile(String profile) {
		if (profile == null || profile.isEmpty()) {
			throw new IllegalArgumentException("Profile must not be empty");
		}
		this.activeProfiles.add(profile);
	}

	/**
	 * 显式设置过的优先；没设置过才去问属性 {@code spring.profiles.active}（逗号分隔）。
	 * <p>
	 * 这个「显式 > 属性」的优先级是真实 {@code AbstractEnvironment} 的顺序，也是「代码里 setActiveProfiles
	 * 能覆盖配置文件」的原因。
	 */
	@Override
	public String[] getActiveProfiles() {
		if (!this.activeProfiles.isEmpty()) {
			return this.activeProfiles.toArray(new String[0]);
		}
		String configured = getProperty("spring.profiles.active");
		if (configured == null || configured.isBlank()) {
			return new String[0];
		}
		return configured.split("\\s*,\\s*");
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
