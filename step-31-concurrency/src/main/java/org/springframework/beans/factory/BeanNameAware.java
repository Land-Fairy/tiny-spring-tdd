package org.springframework.beans.factory;

/**
 * 「告诉我，我在容器里叫什么名字」。
 *
 * <p>
 * 为什么一个 Bean 需要知道自己的名字？真实场景里最常见的三个：
 * <ul>
 * <li>打日志要能定位到「哪个 Bean 报的错」（而不是只看到类名）；</li>
 * <li>需要以名字为键去查别的东西（缓存、监控指标、JMX 的 MBean 名）；</li>
 * <li>要判断自己是不是被起了别名（同一个类注册了两次）。</li>
 * </ul>
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code BeanNameAware}（52 行，方法在 :50）。
 *
 * @see org.springframework.beans.factory.BeanNameAware
 */
public interface BeanNameAware extends Aware {

	/**
	 * 容器把这个 Bean 的名字告诉它。
	 * @param name 注册时用的名字（`id` 属性）
	 */
	void setBeanName(String name);

}
