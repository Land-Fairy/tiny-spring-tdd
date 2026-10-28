package org.springframework.samples.ch04;

/**
 * 有两个构造器的类：用来观察容器「如何在多个构造器之间做选择」。
 *
 * <p>
 * <b>为什么需要它</b>：真实 Spring 的 {@code ConstructorResolver} 里那段代码最有意思的地方不是「怎么注入」，
 * 而是「注入失败时怎么办」——它会把异常咽下去，换下一个构造器再试。这个类就是用来把那段逻辑逼出来的： 只注册 audit
 * 时容器应退到单参数构造器，两个依赖都注册时容器应优先选参数更多的那一个。
 */
public class MultiConstructor {

	private final GreetingAudit audit;

	private final GreetingRepository repository;

	/**
	 * 退路：只有一个依赖。
	 * @param audit 记账本
	 */
	public MultiConstructor(GreetingAudit audit) {
		this(audit, null);
	}

	/**
	 * 首选：依赖更多，构造出的对象更完整。
	 * @param audit 记账本
	 * @param repository 仓库
	 */
	public MultiConstructor(GreetingAudit audit, GreetingRepository repository) {
		this.audit = audit;
		this.repository = repository;
	}

	/**
	 * 记账本。
	 * @return 记账本
	 */
	public GreetingAudit audit() {
		return this.audit;
	}

	/**
	 * 是否拿到了仓库（用来判断容器选了哪个构造器）。
	 * @return 拿到则为 {@code true}
	 */
	public boolean hasRepository() {
		return this.repository != null;
	}

}
