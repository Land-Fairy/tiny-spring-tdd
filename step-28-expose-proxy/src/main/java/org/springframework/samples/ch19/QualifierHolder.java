package org.springframework.samples.ch19;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 用 {@link Qualifier} 点名要哪一个。
 */
public class QualifierHolder {

	@Autowired
	@Qualifier("chineseGreeter")
	private Greeter greeter;

	/**
	 * 被注入的招呼器。
	 * @return 招呼器
	 */
	public Greeter greeter() {
		return this.greeter;
	}

}
