package org.springframework.samples.ch13;

import org.springframework.beans.factory.DisposableBean;

/**
 * 销毁时抛异常的 Bean：用来验证「一个 Bean 的销毁失败不会中断整个关闭流程」。
 */
public class FailingDestroyBean implements DisposableBean {

	public static int destroyAttempts;

	@Override
	public void destroy() {
		destroyAttempts++;
		throw new IllegalStateException("destroy exploded");
	}

}
