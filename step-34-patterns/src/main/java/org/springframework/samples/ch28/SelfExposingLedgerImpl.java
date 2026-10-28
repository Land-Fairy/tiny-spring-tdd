package org.springframework.samples.ch28;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.aop.framework.AopContext;

/**
 * 「走代理的自调用」：{@code deposit} 不再用 {@code this}，而是从 {@link AopContext} 取回代理再调。
 * <p>
 * 代价很实在：业务代码必须知道「自己被 AOP 包着」这件事，还要强转回接口。 这就是真实项目里 {@code AopContext.currentProxy()}
 * 常常被抱怨「脏」的原因 —— 但它确实是标准正解之一。
 */
public class SelfExposingLedgerImpl implements Ledger {

	private int total;

	private final List<String> auditTrail = new ArrayList<>();

	@Override
	public void deposit(int amount) {
		this.total += amount;
		// 取回代理，再调一次 —— 这次调用会重新经过拦截器链。
		((Ledger) AopContext.currentProxy()).appendAudit("deposit " + amount);
		((Ledger) AopContext.currentProxy()).appendAudit("deposit " + amount + " (verified)");
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
