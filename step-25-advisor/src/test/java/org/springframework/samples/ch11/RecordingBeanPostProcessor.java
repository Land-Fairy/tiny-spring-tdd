package org.springframework.samples.ch11;

import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * 一个写成 <b>Bean</b> 的后处理器：它在初始化前后各往 {@link Recorder} 里记一笔。
 *
 * <p>
 * 为什么它必须写成 Bean？因为「注册后处理器」这一步（{@code refresh()} 的第 6 步）
 * 要能从容器里按类型找到它——容器怎么知道自己该装哪些后处理器？靠的就是「你自己也是个 Bean」。
 *
 * @see org.springframework.beans.factory.support.PostProcessorRegistrationDelegate
 */
public class RecordingBeanPostProcessor implements BeanPostProcessor {

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) {
		if (bean instanceof Recorder recorder) {
			recorder.record("before:" + beanName);
		}
		return bean;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (bean instanceof Recorder recorder) {
			recorder.record("after:" + beanName);
		}
		return bean;
	}

}
