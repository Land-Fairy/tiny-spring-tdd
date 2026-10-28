package org.springframework.samples.ch04;

/**
 * 最底层的 Bean：它自己没有任何依赖，所以只要有<b>无参构造器</b>就能被造出来。
 *
 * <p>
 * <b>为什么需要它</b>：第 03 章的容器只会调无参构造器，因此所有 Bean 都必须是「零依赖」的。真实应用里这样的类很少，
 * 绝大多数类都需要另外几个对象才能工作。本章要解决的就是「参数怎么办」。
 */
public class GreetingRepository {

	/**
	 * 按名字查一句问候语。
	 * @param name 名字
	 * @return 问候语
	 */
	public String find(String name) {
		return "Hello, " + name;
	}

}
