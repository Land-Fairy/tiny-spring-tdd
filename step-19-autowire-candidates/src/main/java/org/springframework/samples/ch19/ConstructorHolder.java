package org.springframework.samples.ch19;

/**
 * 只有一个构造器、一个注解都没写——容器照样用得起来。
 *
 * <p>
 * 这是真实 Spring 的「单构造器自动选中」：没有备选，就不必问「用哪个」。
 */
public class ConstructorHolder {

	private final Repository repository;

	/**
	 * 唯一的构造器，没有 {@code @Autowired}，但它是唯一的选择。
	 * @param orderRepository 参数名要与容器里的 Bean 名字对得上（第 04 章的规矩）
	 */
	public ConstructorHolder(Repository orderRepository) {
		this.repository = orderRepository;
	}

	/**
	 * 装配结果。
	 * @return 走的是哪个构造器、装配了谁
	 */
	public String source() {
		return "constructor:" + this.repository.name();
	}

}
