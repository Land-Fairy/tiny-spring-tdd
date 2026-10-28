package org.springframework.samples.ch28;

import java.util.List;

import org.springframework.aop.framework.AopContext;

/**
 * 取回代理之后立刻抛异常，用来验证「恢复现场」写在 {@code finally} 里而不是句末。
 */
public class FailingLedgerImpl implements Ledger {

	@Override
	public void deposit(int amount) {
		AopContext.currentProxy();
		throw new IllegalStateException("账本坏了");
	}

	@Override
	public int total() {
		return 0;
	}

	@Override
	public void appendAudit(String entry) {
	}

	@Override
	public List<String> auditTrail() {
		return List.of();
	}

}
