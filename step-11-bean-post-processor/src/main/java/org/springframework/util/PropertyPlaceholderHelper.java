package org.springframework.util;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 把文本里的 {@code ${name}} 换成 {@link PlaceholderResolver} 给出的值。
 *
 * <p>
 * 四个能力，每个都对应一条真实需求：
 * <ul>
 * <li><b>默认值</b>：{@code ${db.url:jdbc:hsqldb:mem:test}} —— 冒号后是缺省值，让「没配也能跑」成为可能</li>
 * <li><b>嵌套</b>：{@code ${${stage}.url}} —— 先解析内层，再解析外层；靠循环自然完成，不需要显式递归</li>
 * <li><b>宽松/严格</b>：解析不了时保留原文还是抛异常，构造时决定一次</li>
 * <li><b>多个占位符</b>：{@code ${a}-${b}} 一次替换完</li>
 * </ul>
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.util.PropertyPlaceholderHelper}（6.2 中只剩
 * 138 行，是薄壳）——它把全部解析逻辑交给了 6.2 新增的包私有类
 * {@code org.springframework.util.PlaceholderParser}（<b>564 行</b>）。那个类把文本切成 {@code Part}
 * 序列（字面量 / 占位符 / 嵌套占位符），再交给 {@code ParsedValue} 解析。 换句话说：<b>6.2
 * 把「字符串循环查找」升级成了真正的词法解析</b>。 {@code PropertyPlaceholderHelper#parseStringValue}
 * 因此被弃用（{@code @Deprecated(since = "6.2", forRemoval = true)}）。 本书教经典实现（能覆盖 99% 的用法），并在第
 * 5 节说明这处变更。
 *
 * @see org.springframework.core.env.StandardEnvironment
 */
public class PropertyPlaceholderHelper {

	private final String placeholderPrefix;

	private final String placeholderSuffix;

	private final String valueSeparator;

	private final boolean ignoreUnresolvablePlaceholders;

	/**
	 * 构造一个解析器。
	 * @param placeholderPrefix 前缀，通常是 {@code "${"} @param placeholderSuffix 后缀，通常是 {@code
	 * "}"}
	 * @param valueSeparator 默认值分隔符，通常是 {@code ":"}，可为 {@code null}
	 * @param ignoreUnresolvablePlaceholders 解析不了时是否保留原文（{@code true}）还是抛异常（{@code false}）
	 */
	public PropertyPlaceholderHelper(String placeholderPrefix, String placeholderSuffix, String valueSeparator,
			boolean ignoreUnresolvablePlaceholders) {
		this.placeholderPrefix = placeholderPrefix;
		this.placeholderSuffix = placeholderSuffix;
		this.valueSeparator = valueSeparator;
		this.ignoreUnresolvablePlaceholders = ignoreUnresolvablePlaceholders;
	}

	/**
	 * 替换文本中的所有占位符。
	 * @param value 待替换文本
	 * @param placeholderResolver 占位符解析回调
	 * @return 替换后的文本
	 * @throws IllegalArgumentException 严格模式下遇到无法解析的占位符，或发现循环引用时
	 */
	public String replacePlaceholders(String value, PlaceholderResolver placeholderResolver) {
		return replacePlaceholders(value, placeholderResolver, value, new LinkedHashSet<>());
	}

	private String replacePlaceholders(String value, PlaceholderResolver placeholderResolver, String original,
			Set<String> visited) {
		String result = value;
		int start = result.indexOf(this.placeholderPrefix);
		while (start != -1) {
			int end = findMatchingSuffix(result, start);
			if (end == -1) {
				// 前缀没有配对的后缀：当成普通文本，不再处理
				return result;
			}
			String rawName = result.substring(start + this.placeholderPrefix.length(), end);
			// 名字本身可能还含占位符（${${stage}.url}）：先解析名字
			String name = replacePlaceholders(rawName, placeholderResolver, original, visited);
			if (!visited.add(name)) {
				throw new IllegalArgumentException(
						"Circular placeholder reference '" + name + "' in property definitions");
			}
			String propVal;
			try {
				propVal = placeholderResolver.resolvePlaceholder(name);
				if (propVal == null) {
					propVal = resolveDefault(name, placeholderResolver, original, visited);
				}
			}
			finally {
				visited.remove(name);
			}
			if (propVal == null) {
				if (this.ignoreUnresolvablePlaceholders) {
					// 保留原文，但必须跳过它继续往后找，否则原地死循环
					start = result.indexOf(this.placeholderPrefix, start + this.placeholderPrefix.length());
					continue;
				}
				throw new IllegalArgumentException(
						"Could not resolve placeholder '" + name + "' in value \"" + original + "\"");
			}
			result = result.substring(0, start) + propVal + result.substring(end + this.placeholderSuffix.length());
			// 替换进来的值本身也可能含占位符（${{...}} 或属性值里再写占位符），从插入点继续
			start = result.indexOf(this.placeholderPrefix, start);
		}
		return result;
	}

	/**
	 * 找到与 {@code start} 处的<b>前缀</b>配对的<b>后缀</b>位置，认得嵌套。
	 * <p>
	 * 简单写法是 {@code indexOf(suffix, start)}，但它在 {@code ${${stage}.url}} 上会错：第一个 {@code }}
	 * 闭的是内层占位符。这里数一下嵌套层数，只有回到最外层才认。
	 * @param text 文本
	 * @param start 前缀所在下标
	 * @return 配对后缀的下标，找不到返回 {@code -1}
	 */
	private int findMatchingSuffix(String text, int start) {
		int index = start + this.placeholderPrefix.length();
		int nested = 0;
		while (index < text.length()) {
			if (text.startsWith(this.placeholderPrefix, index)) {
				nested++;
				index += this.placeholderPrefix.length();
				continue;
			}
			if (text.startsWith(this.placeholderSuffix, index)) {
				if (nested == 0) {
					return index;
				}
				nested--;
				index += this.placeholderSuffix.length();
				continue;
			}
			index++;
		}
		return -1;
	}

	private String resolveDefault(String name, PlaceholderResolver placeholderResolver, String original,
			Set<String> visited) {
		if (this.valueSeparator == null) {
			return null;
		}
		int separatorIndex = name.indexOf(this.valueSeparator);
		if (separatorIndex == -1) {
			return null;
		}
		String actualName = name.substring(0, separatorIndex);
		String defaultValue = name.substring(separatorIndex + this.valueSeparator.length());
		String propVal = placeholderResolver.resolvePlaceholder(actualName);
		return (propVal != null) ? propVal : replacePlaceholders(defaultValue, placeholderResolver, original, visited);
	}

	/**
	 * 占位符解析回调。
	 */
	@FunctionalInterface
	public interface PlaceholderResolver {

		/**
		 * 把一个占位符名解析成值。
		 * @param placeholderName 占位符名（不含 {@code ${}} 与默认值）
		 * @return 值，解析不了返回 {@code null}
		 */
		String resolvePlaceholder(String placeholderName);

	}

}
