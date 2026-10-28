package org.springframework.samples.ch17;

import org.springframework.context.annotation.Service;

/**
 * 标 {@code @Service} 的服务。
 *
 * <p>
 * 注意 {@code @Service} 本身<b>不是</b> {@code @Component} 的子接口——它头上标着 {@code @Component}。
 * 「派生注解」这个说法就是这么来的，扫描器必须能把这一层看穿。
 */
@Service
public class GreetingService {

	/**
	 * 打招呼。
	 * @param name 名字
	 * @return 招呼语
	 */
	public String greet(String name) {
		return "Hello, " + name;
	}

}
