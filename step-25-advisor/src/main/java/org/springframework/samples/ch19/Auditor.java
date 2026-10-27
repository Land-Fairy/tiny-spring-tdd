package org.springframework.samples.ch19;

/**
 * 容器里<b>一个实现都没有</b>的接口：演示「无候选」。
 */
public interface Auditor {

	/**
	 * 审计。
	 * @return 审计结果
	 */
	String audit();

}
