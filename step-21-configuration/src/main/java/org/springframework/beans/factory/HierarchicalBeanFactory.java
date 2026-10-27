package org.springframework.beans.factory;

/**
 * 加一层：**我上面还有一个容器**。
 *
 * <p>
 * 两个方法，但它们回答的是完全不同的问题，混起来就会写错回退逻辑：
 * <ul>
 * <li>{@link #getParentBeanFactory()}：父容器是谁（可能为 {@code null}）；
 * <li>{@link #containsLocalBean(String)}：<b>我这里</b>有没有（不含父容器）。
 * </ul>
 *
 * <p>
 * 为什么要和 {@code containsBean} 分开？因为 {@code containsBean} 是「这一族容器里有没有」，
 * 是递归的；{@code containsLocalBean} 是「我自己有没有」，不递归。真实 Spring 用后者来避免 「父子容器里都有同名 Bean
 * 时无限回退」这类错误。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.HierarchicalBeanFactory}（53 行，2 个方法）。
 * 本书与真实一致——这一层小得不能再小了。注意真实 Spring 的 {@code containsLocalBean} 有一个 {@code @Override} 指向
 * {@code BeanFactory#containsBean} 的语义澄清，这也是它在接口文档里最强调的一句。
 *
 * @see BeanFactory
 */
public interface HierarchicalBeanFactory extends BeanFactory {

	/**
	 * 父容器。
	 * @return 父容器，没有则为 {@code null}
	 */
	BeanFactory getParentBeanFactory();

	/**
	 * 只看本容器（不看父容器）里有没有这个名字。
	 * @param name Bean 的名字
	 * @return 本容器有则为 {@code true}
	 */
	boolean containsLocalBean(String name);

}
