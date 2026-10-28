package org.springframework.core.env;

/**
 * 可以<b>改</b>的环境：多加一个「设置激活 profile」的能力。
 *
 * <p>
 * 真实 Spring 把它单独拆成 {@code ConfigurableEnvironment}（159 行），是因为<b>读环境的代码不该拿到写环境的能力</b>： 业务
 * Bean 只需要 {@code getProperty}，只有启动代码才该调 {@code setActiveProfiles}。
 * 拆接口比在运行时判断「你是谁」更可靠——类型系统替你记住这条规矩。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.env.ConfigurableEnvironment}。
 *
 * @see StandardEnvironment
 * @see org.springframework.core.env.ConfigurableEnvironment
 */
public interface ConfigurableEnvironment extends Environment {

	/**
	 * 整体替换激活的 profile。
	 * @param profiles profile 名字
	 */
	void setActiveProfiles(String... profiles);

	/**
	 * 追加一个激活的 profile。
	 * @param profile profile 名字
	 */
	void addActiveProfile(String profile);

}
