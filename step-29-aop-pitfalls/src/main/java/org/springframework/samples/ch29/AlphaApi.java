package org.springframework.samples.ch29;

/** 让 {@link Alpha} 有接口可代理 —— 循环依赖的场景必须能造出代理，才能演示「提前引用不是代理」。 */
public interface AlphaApi {

	String hello();

}
