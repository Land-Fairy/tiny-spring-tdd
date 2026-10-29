package org.springframework.core.env;

/**
 * 应用的<b>环境</b>：一个带优先级的属性链 + 占位符解析。
 *
 * <p>
 * <b>为什么需要它</b>：容器在造对象之前就要知道一些「外部世界的值」——数据库地址、开关、端口。这些值不能写进代码，也不该写死在 XML
 * 里。{@code Environment} 就是容器访问它们的唯一入口。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.env.Environment}（152 行，只加
 * {@code getActiveProfiles} / {@code getDefaultProfiles}）与
 * {@code ConfigurableEnvironment}（159 行，加
 * {@code getPropertySources}、{@code setActiveProfiles}）。本书把「可配置」的那部分直接并进
 * {@code Environment}， 因为本书没有 profile（Out of Scope）。
 *
 * @see StandardEnvironment
 * @see org.springframework.core.env.Environment
 */
public interface Environment extends PropertyResolver {

	/**
	 * 属性来源链。
	 * @return 可修改的来源集合
	 */
	MutablePropertySources getPropertySources();

}
