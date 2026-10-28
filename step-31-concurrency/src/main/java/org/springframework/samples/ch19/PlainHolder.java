package org.springframework.samples.ch19;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 没有任何消歧注解：只能靠 {@code @Primary} 或优先级选出来。
 */
public class PlainHolder {

	@Autowired
	private Greeter greeter;

	/**
	 * 被选中的招呼器。
	 * @return 招呼器
	 */
	public Greeter greeter() {
		return this.greeter;
	}

}
