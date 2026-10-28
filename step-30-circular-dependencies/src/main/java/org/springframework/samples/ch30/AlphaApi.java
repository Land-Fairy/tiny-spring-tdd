/*
 * 本书教学代码：tiny-spring-tdd
 */

package org.springframework.samples.ch30;

/**
 * 第 30 章的「业务接口」。有接口是为了让 JDK 代理能工作（第 24 章）。
 */
public interface AlphaApi {

	/**
	 * 打个招呼。
	 * @return 固定字符串
	 */
	String hello();

}
