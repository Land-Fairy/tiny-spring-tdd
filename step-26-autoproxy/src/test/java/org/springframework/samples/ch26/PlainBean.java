package org.springframework.samples.ch26;

/**
 * 一个不会命中任何切点的 Bean：它存在的意义是<b>不被代理</b>。
 */
public class PlainBean {

	public String describe() {
		return "plain";
	}

}
