package org.springframework.samples.ch10;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * 一个只记录「我什么时候被调用、那时谁已经被造出来了」的后处理器。
 */
public class RecordingPostProcessor implements BeanFactoryPostProcessor {

	private static final List<String> CALLS = new ArrayList<>();

	private static volatile boolean targetAlreadyCreated;

	/**
	 * 重置记录。
	 */
	public static void reset() {
		CALLS.clear();
		targetAlreadyCreated = false;
	}

	/**
	 * 被调用过的次数。
	 * @return 次数
	 */
	public static int callCount() {
		return CALLS.size();
	}

	/**
	 * 调用发生时，目标 Bean 是否已经被创建。
	 * @return 已创建返回 {@code true}
	 */
	public static boolean wasTargetAlreadyCreated() {
		return targetAlreadyCreated;
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		CALLS.add("recording");
		targetAlreadyCreated = beanFactory.containsSingleton("jdbc");
	}

}
