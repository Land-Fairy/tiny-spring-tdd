package org.springframework.beans.factory;

/**
 * 「把你自己（工厂）给我」——容器把自己递进去的回调。
 *
 * <p>
 * 这是本章最关键的一条路：它让一个 Bean 能在<b>运行时</b>按需向容器要别的对象 （典型用法：延迟取 Bean、按名字查、判断某个 Bean 在不在）。
 *
 * <p>
 * 它和 {@code @Autowired} 的差别是本章的主题：<b>这里喂的是「设施」，不是「协作对象」</b>。
 * 工厂是框架自己的一部分，它没有图纸、不在容器里，所以它天然不是「可注入的候选」， 只能由容器主动递过去（真实 Spring 另有一条
 * {@code registerResolvableDependency} 把它变成可注入的， 见 {@code AbstractApplicationContext}
 * :743，本书没做）。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code BeanFactoryAware}（55 行，方法在 :53）。
 *
 * @see org.springframework.beans.factory.BeanFactoryAware
 */
public interface BeanFactoryAware extends Aware {

	/**
	 * 容器把工厂递给这个 Bean。回调发生在属性填充之后、初始化回调之前。
	 * @param beanFactory 拥有这个 Bean 的工厂
	 */
	void setBeanFactory(BeanFactory beanFactory);

}
