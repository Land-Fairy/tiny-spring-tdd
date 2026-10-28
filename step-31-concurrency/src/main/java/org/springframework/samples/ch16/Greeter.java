package org.springframework.samples.ch16;

/**
 * 一个最简单的协作对象，用来观察「后处理器按什么顺序包裹它」。
 */
public interface Greeter {

	/**
	 * 打招呼。
	 * @return 招呼语
	 */
	String greet();

}
