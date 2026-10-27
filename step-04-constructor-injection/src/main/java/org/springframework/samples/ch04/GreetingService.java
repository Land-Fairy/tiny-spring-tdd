package org.springframework.samples.ch04;

/**
 * 需要两个依赖的 Bean：这就是第 03 章的容器造不出来的那种类。
 *
 * <p>
 * <b>为什么需要它</b>：它没有无参构造器。要让容器造出它，容器必须回答两个新问题：
 * <ol>
 * <li>构造器有几个参数、每个参数<b>叫什么名字</b>；
 * <li>容器里有没有一个<b>同名的 Bean</b> 可以塞进去。
 * </ol>
 * 两个问题都答得上，才谈得上「自动装配」。
 */
public class GreetingService {

	private final GreetingRepository repository;

	private final GreetingAudit audit;

	/**
	 * 唯一的构造器，需要两个参数。
	 * @param repository 查问候语用的仓库
	 * @param audit 记账本
	 */
	public GreetingService(GreetingRepository repository, GreetingAudit audit) {
		this.repository = repository;
		this.audit = audit;
	}

	/**
	 * 问候一个人，并记一笔账。
	 * @param name 名字
	 * @return 问候语
	 */
	public String greet(String name) {
		this.audit.record(name);
		return this.repository.find(name);
	}

}
