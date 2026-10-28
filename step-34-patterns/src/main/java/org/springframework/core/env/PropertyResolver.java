package org.springframework.core.env;

/**
 * 「按名字查值」与「解析占位符」的统一契约。
 *
 * <p>
 * <b>为什么把两者放同一个接口</b>：它们天生配套。{@code ${db.url}} 里的 {@code db.url} 是一次属性查询，
 * 而「带默认值的占位符」「嵌套占位符」的规则又需要查值能力。真实 Spring 里这两件事分别是 {@code PropertyResolver}（查值 + 解析）与
 * {@code ConfigurablePropertyResolver}（配置前缀、分隔符、严格性）， 本书合成一个。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.env.PropertyResolver}（114 行）、
 * {@code AbstractPropertyResolver}（388 行）。
 *
 * @see StandardEnvironment
 */
public interface PropertyResolver {

	/**
	 * 是否存在某个属性。
	 * @param key 属性名
	 * @return 存在返回 {@code true}
	 */
	boolean containsProperty(String key);

	/**
	 * 查一个属性。
	 * @param key 属性名
	 * @return 值，不存在时返回 {@code null}
	 */
	String getProperty(String key);

	/**
	 * 查一个属性，缺省时用默认值。
	 * @param key 属性名
	 * @param defaultValue 默认值
	 * @return 值
	 */
	String getProperty(String key, String defaultValue);

	/**
	 * 查一个必须存在的属性。
	 * @param key 属性名
	 * @return 值
	 * @throws IllegalStateException 属性不存在时
	 */
	String getRequiredProperty(String key) throws IllegalStateException;

	/**
	 * 解析文本里的 {@code ${...}}，解析不了的原样保留。
	 * @param text 待解析文本
	 * @return 解析后的文本
	 */
	String resolvePlaceholders(String text);

	/**
	 * 解析文本里的 {@code ${...}}，解析不了就抛异常。
	 * @param text 待解析文本
	 * @return 解析后的文本
	 * @throws IllegalArgumentException 存在无法解析的占位符时
	 */
	String resolveRequiredPlaceholders(String text) throws IllegalArgumentException;

}
