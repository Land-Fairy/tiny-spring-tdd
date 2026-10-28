package org.springframework.samples.ch29;

/** 有接口的类：JDK 代理可行，但 {@code final} 方法仍然拦不住。 */
public interface Ticker {

	void beat();

	void tick();

}
