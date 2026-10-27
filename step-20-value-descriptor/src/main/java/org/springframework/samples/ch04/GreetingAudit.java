package org.springframework.samples.ch04;

import java.util.ArrayList;
import java.util.List;

/**
 * 第二个零依赖 Bean：记录被问候过的名字。
 *
 * <p>
 * <b>为什么需要它</b>：一个"装配成功"的对象长什么样，光看返回值不够。有了这个记账本，测试就能反过来证明 {@link GreetingService}
 * 手里拿的那两个依赖<b>确实是被容器塞进去的那两个</b>，而不是自己 new 的。
 */
public class GreetingAudit {

	private final List<String> seen = new ArrayList<>();

	/**
	 * 记一笔。
	 * @param name 被问候的名字
	 */
	public void record(String name) {
		this.seen.add(name);
	}

	/**
	 * 已记录的名字，按发生顺序。
	 * @return 名字列表
	 */
	public List<String> seen() {
		return new ArrayList<>(this.seen);
	}

}
