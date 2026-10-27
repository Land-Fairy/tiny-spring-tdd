package org.springframework.samples.ch19;

/**
 * 名为 {@code orderRepository} 的候选。
 */
public class OrderRepository implements Repository {

	@Override
	public String name() {
		return "order";
	}

}
