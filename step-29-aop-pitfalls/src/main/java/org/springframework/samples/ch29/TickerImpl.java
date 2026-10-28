package org.springframework.samples.ch29;

/**
 * {@link #tick()} 是 {@code final} 方法 —— 它<b>能</b>被 JDK 代理拦（因为代理是实现接口的另一个类， 根本不需要覆写它），但
 * CGLIB 代理<b>不能</b>（子类无法覆写 final 方法）。这个差异正是「网上说的 final 方法不可代理」时好时坏的根源。
 */
public class TickerImpl implements Ticker {

	@Override
	public void beat() {
	}

	@Override
	public final void tick() {
	}

}
