package org.springframework.samples.ch17.probe;

import org.springframework.context.annotation.Component;

/**
 * 探针一：只要被 JVM <b>初始化</b>，{@link Counter#initialized} 就 +1。
 *
 * <p>
 * 「读字节码」与「反射加载」的区别，在本章就靠这个数字说话。
 */
@Component
public class InitProbe {

	static {
		Counter.initialized++;
	}

	/**
	 * 无意义的方法，只为了让类看起来正常。
	 * @return 固定字符串
	 */
	public String name() {
		return "init-probe";
	}

}
