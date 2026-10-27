package org.springframework.beans.factory;

/**
 * 所有 {@code *Aware} 接口的<b>共同父接口</b>——它自己一个方法都没有。
 *
 * <p>
 * 为什么需要这么个空接口？因为容器要能「一次判断、分别回调」：
 *
 * <pre>
 * if (bean instanceof Aware) {
 *     if (bean instanceof BeanNameAware beanNameAware) { ... }
 *     if (bean instanceof BeanFactoryAware beanFactoryAware) { ... }
 * }
 * </pre>
 *
 * 先判父接口，就能在「绝大多数 Bean 都不是 Aware」的常见情况下只做一次 {@code instanceof}。 这是真实 Spring
 * 的写法（{@code AbstractAutowireCapableBeanFactory#invokeAwareMethods} :1833）。
 *
 * <p>
 * <b>它不提供任何默认功能</b>——这句话来自真实 Spring 自己的 Javadoc，值得原样记住： 「merely implementing Aware
 * provides no default functionality. Rather, processing must be done explicitly, for
 * example in a BeanPostProcessor」。也就是说，回调是<b>别人</b>（工厂或后处理器） 调用的，你只负责实现方法。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.factory.Aware}（37 行，纯标记）。
 *
 * @see org.springframework.beans.factory.BeanNameAware
 * @see org.springframework.beans.factory.BeanFactoryAware
 * @see org.springframework.context.ApplicationContextAware
 */
public interface Aware {

}
