package org.springframework.core.env;

/**
 * 应用的<b>环境</b>：一个带优先级的属性链 + 占位符解析。
 *
 * <p>
 * <b>为什么需要它</b>：容器在造对象之前就要知道一些「外部世界的值」——数据库地址、开关、端口。这些值不能写进代码，也不该写死在 XML
 * 里。{@code Environment} 就是容器访问它们的唯一入口。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.env.Environment}（只加
 * {@code getActiveProfiles} / {@code getDefaultProfiles}）与
 * {@code ConfigurableEnvironment}（再加
 * {@code getPropertySources}、{@code setActiveProfiles}）。本书把「可配置」的那部分直接并进
 * {@code Environment}：接口一多，读者就要在两份几乎一样的 javadoc 之间做对照，收益不抵成本。 代价是「只读视图」这个语义消失了——真实 Spring
 * 里被注入 `Environment` 的 Bean 改不了 profile。
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

	/**
	 * 当前激活的 profile 名字。
	 * <p>
	 * 没显式设置时，实现类会去看 {@code spring.profiles.active} 属性——这就是「环境变量 / 命令行参数能切换
	 * profile」的全部秘密，不是魔法。
	 * @return 激活的 profile 名字，可能为空数组
	 */
	String[] getActiveProfiles();

}
