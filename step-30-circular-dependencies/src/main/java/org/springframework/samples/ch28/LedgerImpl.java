package org.springframework.samples.ch28;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 最直白的实现：{@code deposit} 内部用 {@code this.appendAudit(...)}。
 * <p>
 * {@code this} 是<b>目标</b>，不是代理。所以这次调用不会经过任何拦截器 —— 这就是本章要复现的失效。
 */
public class LedgerImpl implements Ledger {

	private int total;

	private final List<String> auditTrail = new ArrayList<>();

	@Override
	public void deposit(int amount) {
		this.total += amount;
		this.appendAudit("deposit " + amount);
	}

	@Override
	public int total() {
		return this.total;
	}

	@Override
	public void appendAudit(String entry) {
		this.auditTrail.add(entry);
	}

	@Override
	public List<String> auditTrail() {
		return Collections.unmodifiableList(this.auditTrail);
	}

}
