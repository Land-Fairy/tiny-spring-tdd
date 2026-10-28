package org.springframework.core;

/**
 * 异常链的纯函数工具箱：<b>不依赖任何异常类型</b>，只沿着 {@code getCause()} 往下走。
 *
 * <p>
 * <b>为什么需要它</b>：一旦开始「包装异常」，就会出现两个问题—— ①<b>链有多深</b>？用户只想看最内层那一行，但栈顶永远是最后一层包装；
 * ②<b>「最内层」有两个意思</b>：一个是「最深的 cause」（可能不存在），一个是「能拿到的最具体的那个」（永远存在）。 这两个语义差一个
 * {@code null}，恰好是排错时最容易用错的地方，所以必须拆成两个方法。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.NestedExceptionUtils}（91 行）。
 * {@code getRootCause}/{@code getMostSpecificCause} 自 4.3.9 起是静态工具方法；
 * {@code buildMessage} 在 6.0 起被标记 {@code @Deprecated}——因为「nested exception is …」这种
 * 拼接会把原因重复三遍（原因已经在栈里了），新版改为「每层只写自己知道的上下文」。
 */
public abstract class NestedExceptionUtils {

	/**
	 * 沿 {@code getCause()} 走到最深处；没有 cause 时返回 {@code null}。
	 * <p>
	 * 循环条件里的 {@code cause != rootCause} 是一个防御：某些库会让异常自己当自己的 cause
	 * （{@code SQLException#getNextException} 一类的历史遗留），没有这道判断就会死循环。
	 * @param original 起始异常，可为 {@code null}
	 * @return 最内层异常，可能为 {@code null}
	 */
	public static Throwable getRootCause(Throwable original) {
		if (original == null) {
			return null;
		}
		Throwable rootCause = null;
		Throwable cause = original.getCause();
		while (cause != null && cause != rootCause) {
			rootCause = cause;
			cause = cause.getCause();
		}
		return rootCause;
	}

	/**
	 * 「能拿到的最具体的那个」：有 cause 就给最内层，没有就给它自己。
	 * <p>
	 * 它<b>永不返回 null</b>，所以调用方可以放心地对它做 {@code instanceof} 判断—— 这正是排错代码里最常用的那个动作。
	 * @param original 起始异常
	 * @return 最具体的原因（非 {@code null}）
	 */
	public static Throwable getMostSpecificCause(Throwable original) {
		Throwable rootCause = getRootCause(original);
		return (rootCause != null ? rootCause : original);
	}

	/**
	 * 老式拼接：{@code "消息; nested exception is 原因"}。
	 * <p>
	 * 保留它是为了展示<b>反面教材</b>：三层包装之后，同一句「找不到 bean」会在消息里出现三次， 而栈里本来就有。真实 Spring 6.0 起已弃用。
	 * @param message 本层消息，可为 {@code null}
	 * @param cause 原因，可为 {@code null}
	 * @return 拼接后的消息
	 * @deprecated 6.0 起不再推荐，原因会被重复
	 */
	@Deprecated(since = "33")
	public static String buildMessage(String message, Throwable cause) {
		if (cause == null) {
			return message;
		}
		StringBuilder sb = new StringBuilder(64);
		if (message != null) {
			sb.append(message).append("; ");
		}
		sb.append("nested exception is ").append(cause);
		return sb.toString();
	}

}
