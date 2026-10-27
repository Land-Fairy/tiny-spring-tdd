package org.springframework.samples.ch22;

/**
 * 同一份契约，两套实现：一套给 dev、一套给 prod。
 */
public interface GreetingPort {

	/**
	 * 打招呼。
	 * @return 招呼语
	 */
	String greet();

}
