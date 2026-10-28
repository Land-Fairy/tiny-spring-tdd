package org.springframework.core;

/**
 * 能带原因的运行时异常基类：<b>它存在的唯一目的是把「链」变成 API</b>。
 *
 * <p>
 * <b>为什么需要它</b>：{@code RuntimeException} 有 {@code getCause()}，但用起来很笨重——
 * 想知道「最内层是什么类型」得自己写一个 while 循环，而且每一处写法都不一样。 把这件事收进基类，整个框架就只需要一套语义：
 *
 * <ul>
 * <li>{@link #getRootCause()}：最内层，可能 {@code null}；</li>
 * <li>{@link #getMostSpecificCause()}：最内层或自己，永不为 {@code null}；</li>
 * <li>{@link #contains(Class)}：「链上有没有这一类」。</li>
 * </ul>
 *
 * <p>
 * <b>为什么是抽象类</b>：真实 Spring 的注释写得很直白——「This class is abstract to force the programmer to
 * extend the class」，即它没有「业务含义」，只是一个实现细节的容器， 不该被直接 {@code new}。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.NestedRuntimeException} （117 行；同名兄弟
 * {@code NestedCheckedException} 存在的原因是 Java 只有一个父类）。
 *
 * @see NestedExceptionUtils
 */
public abstract class NestedRuntimeException extends RuntimeException {

	/**
	 * 只带消息。
	 * @param message 详情
	 */
	public NestedRuntimeException(String message) {
		super(message);
	}

	/**
	 * 带消息与原因。
	 * @param message 详情
	 * @param cause 原因
	 */
	public NestedRuntimeException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * 最内层原因，可能为 {@code null}。
	 * @return 最内层异常
	 */
	public Throwable getRootCause() {
		return NestedExceptionUtils.getRootCause(this);
	}

	/**
	 * 能拿到的最具体原因（链为空时返回自己）。
	 * @return 最具体的原因
	 */
	public Throwable getMostSpecificCause() {
		Throwable rootCause = getRootCause();
		return (rootCause != null ? rootCause : this);
	}

	/**
	 * 这条链上有没有指定类型的异常。
	 * <p>
	 * 三个分支的顺序值得看：先看自己，再看「cause 是不是另一个 NestedRuntimeException」
	 * （是就递归），最后对普通异常做线性遍历。为什么要区分？因为递归版能复用别人的实现， 而线性版必须自己防「cause == 自己」的死循环。
	 * @param exceptionType 要找的类型，可为 {@code null}（返回 {@code false}）
	 * @return 链上是否存在该类型
	 */
	public boolean contains(Class<?> exceptionType) {
		if (exceptionType == null) {
			return false;
		}
		if (exceptionType.isInstance(this)) {
			return true;
		}
		Throwable cause = getCause();
		if (cause == this) {
			return false;
		}
		if (cause instanceof NestedRuntimeException nested) {
			return nested.contains(exceptionType);
		}
		while (cause != null) {
			if (exceptionType.isInstance(cause)) {
				return true;
			}
			if (cause.getCause() == cause) {
				break;
			}
			cause = cause.getCause();
		}
		return false;
	}

}
