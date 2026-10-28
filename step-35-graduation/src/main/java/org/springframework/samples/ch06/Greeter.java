package org.springframework.samples.ch06;

/**
 * 本章的样本接口：一个只会打招呼的东西。
 *
 * <p>
 * 它存在的唯一目的是给容器里有「多种实现」这件事一个载体——于是 {@code getBeansOfType(Greeter.class)}
 * 才有意义（{@code ListableBeanFactory} 的能力），而 {@code getBean("greeter", Greeter.class)} 只需要
 * {@code BeanFactory}（最小能力）。
 */
public interface Greeter {

	/**
	 * 打招呼。
	 * @param name 名字
	 * @return 打招呼的字符串
	 */
	String greet(String name);

}
