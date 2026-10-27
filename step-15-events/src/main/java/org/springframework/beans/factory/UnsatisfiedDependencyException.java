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
 * {@code BeansException}；本章把异常体系砍成一层， 直接继承 {@code IllegalStateException}——完整的异常体系是第 33
 * 章的主题。
 *
 * @see org.springframework.beans.factory.UnsatisfiedDependencyException
 */
public class UnsatisfiedDependencyException extends IllegalStateException {

	/**
	 * 只带消息。
	 * @param message 说明是哪个 Bean 的哪个参数出了问题
	 */
	public UnsatisfiedDependencyException(String message) {
		super(message);
	}

	/**
	 * 带消息和原始异常。
	 * @param message 说明是哪个 Bean 的哪个参数出了问题
	 * @param cause 最初的失败原因
	 */
	public UnsatisfiedDependencyException(String message, Throwable cause) {
		super(message, cause);
	}

}
