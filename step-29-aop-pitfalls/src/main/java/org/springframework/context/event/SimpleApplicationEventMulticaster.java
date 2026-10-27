package org.springframework.context.event;

import org.springframework.util.ErrorHandler;

/**
 * 同步多播器：一个接一个地调监听器，都在发布者的线程里。
 *
 * <p>
 * 三个决定值得记住：
 * <ol>
 * <li><b>默认不吞异常。</b>没有设置 {@link ErrorHandler} 时，监听器抛什么就往外抛什么—— 于是 {@code publishEvent}
 * 的调用方（可能是 {@code refresh()}）会直接失败。这是刻意的：
 * 启动阶段的事件监听器出错，说明应用没准备好，静默吞掉会让「启动成功了但一半初始化没做」。</li>
 * <li><b>设了 {@link ErrorHandler} 就由它接管</b>，并且<b>剩下的监听器继续跑</b>—— 一个坏监听器不该让别的监听器收不到事件。</li>
 * <li><b>类型不匹配的投递被静默跳过</b>。真实 Spring 靠 {@code ResolvableType} 在投递前筛掉， 本书没有那套解析，只能在
 * {@link ClassCastException} 里判断「这次转换失败是不是因为事件类型不对」。</li>
 * </ol>
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.context.event.SimpleApplicationEventMulticaster} （227
 * 行）。真实版还有 {@code setTaskExecutor}（异步执行）、{@code supportsAsyncExecution} 判定、 以及
 * {@code matchesClassCastMessage} 那段精细的「桥接方法 CCE」判定。
 */
public class SimpleApplicationEventMulticaster extends AbstractApplicationEventMulticaster {

	private ErrorHandler errorHandler;

	/**
	 * 设置异常处理器。设了它就由它接管监听器抛出的异常；不设则直接往外抛。
	 * @param errorHandler 异常处理器
	 */
	public void setErrorHandler(ErrorHandler errorHandler) {
		this.errorHandler = errorHandler;
	}

	/**
	 * 取异常处理器。
	 * @return 异常处理器，可能为 {@code null}
	 */
	protected ErrorHandler getErrorHandler() {
		return this.errorHandler;
	}

	@Override
	public void multicastEvent(ApplicationEvent event) {
		for (ApplicationListener<?> listener : getApplicationListeners(event)) {
			invokeListener(listener, event);
		}
	}

	/**
	 * 调一个监听器，按「有没有异常处理器」分两条路。
	 * @param listener 监听器
	 * @param event 事件
	 */
	protected void invokeListener(ApplicationListener<?> listener, ApplicationEvent event) {
		ErrorHandler handler = getErrorHandler();
		if (handler != null) {
			try {
				doInvokeListener(listener, event);
			}
			catch (Exception ex) {
				handler.handleError(ex);
			}
		}
		else {
			doInvokeListener(listener, event);
		}
	}

	@SuppressWarnings("unchecked")
	private void doInvokeListener(ApplicationListener<?> listener, ApplicationEvent event) {
		try {
			((ApplicationListener<ApplicationEvent>) listener).onApplicationEvent(event);
		}
		catch (ClassCastException ex) {
			// 两种 ClassCastException 必须分开：
			// ① 桥接方法里的转换失败——说明「这个监听器不认这种事件」，跳过它是正确的；
			// ② 监听器自己代码里抛的 CCE——那是真 bug，必须往外抛。
			// 判别方式是看消息里有没有事件类名（真实 Spring 用 matchesClassCastMessage 做同一件事，
			// 判别更严谨：它还比对广播路径里的泛型信息）。这是个脆弱但必要的判别。
			String message = ex.getMessage();
			if (message != null && message.contains(event.getClass().getName())) {
				return;
			}
			throw ex;
		}
	}

}
