package org.springframework.beans;

/**
 * 「这个 Bean 已经废了」等级的异常：<b>创建失败了，重试也没用</b>。
 *
 * <p>
 * <b>为什么中间还要插一层</b>：{@code BeansException} 下面其实有两种语义—— 「查不到」（可以换名字再试）和「造不出来」（再试也没用）。前者以
 * {@code NoSuchBeanException} 为代表，后者都挂在 {@code FatalBeanException} 下。 把这条界线画出来，调用方的 catch
 * 才能写得对：
 *
 * <pre>
 * catch (NoSuchBeanException ex) { /* 名字写错了，提示用户 *\/ }
 * catch (FatalBeanException ex) { /* 配置有问题，把栈打出来 *\/ }
 * </pre>
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.factory.FatalBeanException} （继承
 * {@code BeansException}；{@code BeanCreationException}、{@code BeanDefinitionStoreException}
 * 都在它下面）。
 *
 * @see org.springframework.beans.FatalBeanException
 */
public class FatalBeanException extends BeansException {

	/**
	 * 只带消息。
	 * @param message 详情
	 */
	public FatalBeanException(String message) {
		super(message);
	}

	/**
	 * 带消息与原因。
	 * @param message 详情
	 * @param cause 原因
	 */
	public FatalBeanException(String message, Throwable cause) {
		super(message, cause);
	}

}
