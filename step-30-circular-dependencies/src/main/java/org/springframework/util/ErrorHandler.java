package org.springframework.util;

/**
 * 一个「把异常接住」的策略接口。
 *
 * <p>
 * 它存在的唯一理由：框架里有些地方<b>必须</b>捕获异常（否则一个坏监听器能让整条链路停摆），
 * 但捕获之后不能默默扔掉——得有人决定怎么办。把这个决定抽成接口，调用方就有得选： 记日志、转成指标、或者干脆重新抛出。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.util.ErrorHandler}（24 行，方法
 * {@code handleError}）。
 */
@FunctionalInterface
public interface ErrorHandler {

	/**
	 * 处理一个异常。
	 * @param t 要处理的异常
	 */
	void handleError(Throwable t);

}
