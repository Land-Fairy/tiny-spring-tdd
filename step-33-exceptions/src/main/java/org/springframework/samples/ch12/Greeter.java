package org.springframework.samples.ch12;

/**
 * 一个被 {@link AwareProbe} 在运行时<b>按名字</b>取出来的协作对象。
 *
 * @see org.springframework.samples.ch12.AwareProbe#lookupGreeter()
 */
public interface Greeter {

	/**
	 * 打招呼。
	 * @return 招呼语
	 */
	String greet();

}
