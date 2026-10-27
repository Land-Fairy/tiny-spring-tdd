package org.springframework.beans.factory;

/**
 * 造这个 Bean 的过程失败了——<b>这是容器对外的统一说法</b>。
 *
 * <p>
 * <b>为什么需要它</b>：本章有两层错误。里层（{@code BeanWrapper}）知道「哪个属性、什么类型」， 外层（容器）知道「哪个
 * Bean」。库的调用者通常只关心后者，所以要让外层加壳，并且<b>把里层留在 cause 上</b>： 想看细节的人往下翻，只想定位到哪个 Bean 的人看第一行。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.factory.BeanCreationException}（215
 * 行）， 带 {@code beanName} 与 {@code resourceDescription} 两个上下文字段，并继承
 * {@code BeanCreationException} 体系的根 {@code BeansException}。真实的包装点在
 * {@code AbstractAutowireCapableBeanFactory#applyPropertyValues}（:1757-1760）：
 *
 * <pre>
 * catch (BeansException ex) {
 *     throw new BeanCreationException(mbd.getResourceDescription(), beanName, ex.getMessage(), ex);
 * }
 * </pre>
 *
 * @see org.springframework.beans.factory.BeanCreationException
 */
public class BeanCreationException extends IllegalStateException {

	private final String beanName;

	/**
	 * 只带消息。
	 * @param beanName 出问题的 Bean 名字
	 * @param message 说明哪里失败
	 */
	public BeanCreationException(String beanName, String message) {
		super(message);
		this.beanName = beanName;
	}

	/**
	 * 带消息与原始异常。
	 * @param beanName 出问题的 Bean 名字
	 * @param message 说明哪里失败
	 * @param cause 里层异常（属性访问失败、初始化失败等）
	 */
	public BeanCreationException(String beanName, String message, Throwable cause) {
		super(message, cause);
		this.beanName = beanName;
	}

	/**
	 * 出问题的 Bean 名字。
	 * @return Bean 名字
	 */
	public String getBeanName() {
		return this.beanName;
	}

}
