package org.springframework.samples.ch17.probe;

import org.springframework.context.annotation.Component;

/**
 * 探针二：给反射路径用。它和 {@link InitProbe} 一样会计数，但是独立的类， 所以两个测试互不干扰（JVM 里一个类只会初始化一次）。
 */
@Component
public class ReflectionProbe {

	static {
		Counter.initialized++;
	}

	/**
	 * 无意义的方法，只为了让类看起来正常。
	 * @return 固定字符串
	 */
	public String name() {
		return "reflection-probe";
	}

}
