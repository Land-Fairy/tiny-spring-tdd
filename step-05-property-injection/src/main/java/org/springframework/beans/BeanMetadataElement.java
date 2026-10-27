package org.springframework.beans;

/**
 * 带着「出处」的元数据元素。
 *
 * <p>
 * <b>它在干什么</b>：给元数据（图纸、属性值、构造器参数……）留一个口子，让它能记住自己是从哪儿来的—— 一个 XML 文件里的某一行、一个被 {@code @Bean}
 * 标注的方法、或者干脆什么都没有。
 *
 * <p>
 * <b>为什么需要它</b>：容器报错时最值钱的信息是「这条配置是谁写的」。有了 {@code getSource()}， Spring 才能在异常里印出
 * {@code class path resource [beans.xml]} 这样的定位信息，而不是只告诉你 「有个 Bean 造不出来」。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.BeanMetadataElement}。它的
 * {@code getSource()} 在 6.2.x 里就是一个返回 {@code null} 的 {@code default}
 * 方法（{@code BeanMetadataElement.java:35}）， 需要出处的实现自己去覆盖。本章原样保留这一形态：默认没有出处。
 *
 * @see org.springframework.beans.factory.config.BeanDefinition
 */
public interface BeanMetadataElement {

	/**
	 * 这条元数据的配置出处。
	 * @return 出处对象，默认 {@code null}
	 */
	default Object getSource() {
		return null;
	}

}
