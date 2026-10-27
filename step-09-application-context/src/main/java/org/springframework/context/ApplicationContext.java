package org.springframework.context;

import org.springframework.beans.factory.HierarchicalBeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;

/**
 * 应用上下文：一个<b>会自己启动</b>的容器。
 *
 * <p>
 * 它继承了两层 BeanFactory 能力，自己只加了两个「身份」方法——这个继承关系本身就是本章的第一个知识点：
 * <b>上下文没有发明新的取对象能力，它只负责「把容器准备好」</b>。
 *
 * <p>
 * 对比第 06 章的 {@link ListableBeanFactory}：那时候读者得自己「造工厂 → 读配置 →
 * preInstantiateSingletons」，三步都不能少、顺序还不能错。上下文把这三步收进一个有名字的流程
 * （{@link ConfigurableApplicationContext#refresh()}），并且给这个流程加上「已启动 / 已关闭」的状态。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.context.ApplicationContext}（117 行）， 它
 * extends {@code ListableBeanFactory, HierarchicalBeanFactory, ApplicationEventPublisher,
 * MessageSource, ResourcePatternResolver, EnvironmentCapable}。本书只保留前两个，砍掉：
 * <ul>
 * <li>{@code ApplicationEventPublisher}（事件，第 15 章）</li>
 * <li>{@code MessageSource}（国际化，Out of Scope）</li>
 * <li>{@code ResourcePatternResolver}（`classpath*:` 扫描，第 18 章）</li>
 * <li>{@code EnvironmentCapable}（第 10 章）</li>
 * <li>{@code getId()} / {@code getApplicationName()} / {@code getParent()}</li>
 * </ul>
 *
 * @see ConfigurableApplicationContext
 */
public interface ApplicationContext extends ListableBeanFactory, HierarchicalBeanFactory {

	/**
	 * 这个上下文的显示名。它唯一的用途是<b>出现在异常消息里</b>。
	 * <p>
	 * 为什么值得单独一个方法？因为「has not been refreshed yet」这句话如果不带名字， 在一个有十个上下文的进程里毫无意义。
	 * @return 显示名
	 */
	String getDisplayName();

	/**
	 * 启动时刻（毫秒时间戳），未启动时为 0。
	 * @return 启动时间戳
	 */
	long getStartupDate();

}
