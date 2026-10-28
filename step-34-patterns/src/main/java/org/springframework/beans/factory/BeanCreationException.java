package org.springframework.beans.factory;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.FatalBeanException;

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
public class BeanCreationException extends FatalBeanException {

	private final String beanName;

	/**
	 * 「相关原因」：一次失败可能有多条线索（比如「三个候选都注入失败了」）， cause 只能挂一条，剩下的挂这里。
	 */
	private final List<Throwable> relatedCauses = new ArrayList<>();

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

	/**
	 * 追加一条「相关原因」。
	 * <p>
	 * 真实 Spring 用它来记录「多个候选 Bean 各自为什么失败」——这类信息挂不进 cause 链， 但恰恰是排错时最想看的。
	 * @param ex 相关原因
	 */
	public void addRelatedCause(Throwable ex) {
		this.relatedCauses.add(ex);
	}

	/**
	 * 所有相关原因。
	 * @return 相关原因数组（不为 {@code null}）
	 */
	public Throwable[] getRelatedCauses() {
		return this.relatedCauses.toArray(new Throwable[0]);
	}

}
