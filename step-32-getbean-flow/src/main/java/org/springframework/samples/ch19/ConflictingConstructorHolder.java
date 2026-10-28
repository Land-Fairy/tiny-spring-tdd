package org.springframework.samples.ch19;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 两个构造器都要 {@code required = true}：注解处理器直接拒绝，而不是悄悄挑一个。
 *
 * <p>
 * 「用哪个」没有正确答案的时候，猜一个比报错更糟——用户会把「碰巧能跑」当成「本该如此」。
 */
public class ConflictingConstructorHolder {

	/**
	 * 第一个必需构造器。
	 * @param repository 依赖
	 */
	@Autowired
	public ConflictingConstructorHolder(Repository repository) {
		// 空实现：这个类只用来触发「两个必需构造器」的冲突，永远不会被真正实例化。
	}

	/**
	 * 第二个必需构造器。
	 * @param auditor 依赖
	 */
	@Autowired
	public ConflictingConstructorHolder(Auditor auditor) {
		// 同上。
	}

}
