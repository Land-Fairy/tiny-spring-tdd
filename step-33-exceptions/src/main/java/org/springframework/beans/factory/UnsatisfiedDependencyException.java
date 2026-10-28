package org.springframework.beans.factory;

/**
 * 容器找到了图纸、也找到了要造的类，但<b>凑不齐构造器参数</b>。
 *
 * <p>
 * <b>为什么需要它</b>：这是「配置错误」里最常见的一类，也是最难自己看明白的一类。原始的 {@code NullPointerException} 或
 * {@code IllegalArgumentException} 只会告诉你「某个方法调用失败」， 而真正需要知道的是三件事：哪个
 * Bean、哪个参数、容器里当时有什么。所以这个异常的全部价值就在<b>消息</b>里。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.UnsatisfiedDependencyException} （111
 * 行，4 个构造器，还带一个 {@code InjectionPoint} 字段记录「注入点」）。 它继承 {@code BeanCreationException}，后者继承
 * {@code BeansException}。第 33 章把这条链补齐之后，它终于真的继承 {@code BeanCreationException}， 于是「哪个 Bean
 * + 哪个参数」两个上下文都进了同一个对象。
 *
 * @see org.springframework.beans.factory.UnsatisfiedDependencyException
 */
public class UnsatisfiedDependencyException extends BeanCreationException {

	/**
	 * 只带消息。
	 * @param message 说明是哪个 Bean 的哪个参数出了问题
	 */
	public UnsatisfiedDependencyException(String message) {
		super(null, message);
	}

	/**
	 * 带 Bean 名字与消息——排错时真正有用的形态。
	 * @param beanName 正在创建、但凑不齐依赖的 Bean 名字
	 * @param message 哪个参数/属性出了问题
	 */
	public UnsatisfiedDependencyException(String beanName, String message) {
		super(beanName, message);
	}

	/**
	 * 带 Bean 名字、消息与原始异常。
	 * @param beanName 正在创建、但凑不齐依赖的 Bean 名字
	 * @param message 哪个参数/属性出了问题
	 * @param cause 最初的失败原因
	 */
	public UnsatisfiedDependencyException(String beanName, String message, Throwable cause) {
		super(beanName, message, cause);
	}

	/**
	 * 带消息和原始异常。
	 * @param message 说明是哪个 Bean 的哪个参数出了问题
	 * @param cause 最初的失败原因
	 */
	public UnsatisfiedDependencyException(String message, Throwable cause) {
		super(null, message, cause);
	}

}
