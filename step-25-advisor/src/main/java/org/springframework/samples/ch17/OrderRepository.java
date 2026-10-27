package org.springframework.samples.ch17;

import org.springframework.context.annotation.Repository;

/**
 * 标 {@code @Repository} 的仓储。
 */
@Repository
public class OrderRepository {

	/**
	 * 假装查一次库。
	 * @return 固定字符串
	 */
	public String find() {
		return "order-1";
	}

}
