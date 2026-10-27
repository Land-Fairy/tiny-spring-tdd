package org.springframework.samples.ch11;

/**
 * 一个<b>没有任何实现 Bean</b> 的接口，专门用来演示 {@code @Autowired(required = false)}。
 *
 * <p>
 * 容器里永远找不到它的实例，所以「可选依赖」这条分支一定会被走到。
 *
 * @see org.springframework.samples.ch11.GreetingService
 */
public interface MissingDependency {

	/**
	 * 一个永远不会被调用的方法（因为没有实现）。
	 * @return 永远不会返回
	 */
	String value();

}
