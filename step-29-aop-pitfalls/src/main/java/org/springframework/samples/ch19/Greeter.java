package org.springframework.samples.ch19;

/**
 * 一个有多份实现的接口：候选一多，就得有消歧规则。
 */
public interface Greeter {

	/**
	 * 打招呼。
	 * @return 招呼语
	 */
	String greet();

}
