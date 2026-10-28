package org.springframework.beans.factory.config;

/**
 * 「把一段可能含 {@code ${...}} 的文本，处理成一段文本」。
 *
 * <p>
 * 为什么要有这个接口，而不是直接调 {@code Environment#resolvePlaceholders}？ 因为<b>替换这件事不止一家有话说</b>：
 *
 * <ul>
 * <li>{@code Environment}：{@code ${app.port}} → 系统属性 / 环境变量 / 配置文件里的值</li>
 * <li>{@code PropertySourcesPlaceholderConfigurer}：同一件事，但<b>缺值时要抛异常</b>（严格模式）</li>
 * <li>用户的：把 {@code ${now}} 换成时间戳、把 {@code ${vault:xxx}} 换成密钥管理系统里的值</li>
 * </ul>
 *
 * <p>
 * 如果容器硬写死「调 Environment」，第三种人就得改源码。所以真实 Spring 把它抽成一个
 * <b>单方法接口</b>，容器只维护一个<b>有序列表</b>，一个一个问过去，直到有人把文本改了。
 *
 * <p>
 * <b>这里藏着一个容易被忽略的契约</b>：{@link #resolveStringValue(String)} 只允许返回
 * 「处理过的值」或「返回入参本身」——<b>不允许返回 {@code null}</b>。因为列表是串起来的， 某一环返回 {@code null}
 * {@code resolveEmbeddedValue} 会把 {@code null} 当成结果继续往下传， 结果就是「一句正常的话变成了 null」。真实 Spring
 * 的 Javadoc 为此专门写了一句 「Return the original {@code String} in case of no resolution」。
 *
 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#addEmbeddedValueResolver
 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#resolveEmbeddedValue
 */
@FunctionalInterface
public interface StringValueResolver {

	/**
	 * 解析一段文本。
	 * @param value 原始文本（不为 {@code null}）
	 * @return 解析后的文本；<b>无法解析时返回入参本身</b>，绝不返回 {@code null}
	 */
	String resolveStringValue(String value);

}
