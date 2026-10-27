package org.springframework.core.io;

/**
 * 一段可以读的内容：不管它在 classpath、文件系统、URL，还是内存里。
 *
 * <p>
 * 这是本书第一个「来源无关」的抽象。它的价值不在方法多，而在**调用方不需要知道来源**： XML 解析器只认
 * {@code Resource}，换一个来源实现，解析器一行不用改。
 *
 * <p>
 * 与真实 Spring 的差别：这里砍掉了 {@code getURI()}、{@code contentLength()}、
 * {@code lastModified()}、{@code createRelative()} 与可写资源（{@code WritableResource}），
 * 只留下「判断存在 + 读内容 + 报出自己是谁」这三件事。
 *
 * @see org.springframework.core.io.Resource 真实对应物
 */
public interface Resource extends InputStreamSource {

	/**
	 * 这个资源是否存在。实现必须**不抛异常**——「不存在」是一个正常答案。
	 * @return 存在为 {@code true}
	 */
	boolean exists();

	/**
	 * 这个资源是不是可读的。
	 * @return 可读为 {@code true}
	 */
	default boolean isReadable() {
		return exists();
	}

	/**
	 * 资源路径的最后一段，没有文件名时返回 {@code null}。
	 * @return 文件名或 {@code null}
	 */
	String getFilename();

	/**
	 * 一句话说明「我是谁」，专门用于拼错误消息。
	 * @return 形如 {@code class path resource [ch07/app.properties]}
	 */
	String getDescription();

}
