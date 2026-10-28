package org.springframework.samples.ch28;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.aop.framework.AopContext;

/**
 * 一个很常见的错误写法：从 {@link AopContext} 取回代理之后，强转成<b>实现类</b>。
 * <p>
 * 代理只实现了接口，所以这次强转必然抛 {@code ClassCastException} —— 这也解释了为什么
 * {@code AopContext.currentProxy()} 的返回类型是 {@code Object} 而不是泛型 {@code T}：
 * 框架无法知道你要的是接口还是类，只能让你自己写强转，并在写错时当场炸。
 */
public class CastingLedgerImpl implements Ledger {

	private int total;

	private final List<String> auditTrail = new ArrayList<>();

	@Override
	public void deposit(int amount) {
		this.total += amount;
		((CastingLedgerImpl) AopContext.currentProxy()).appendAudit("deposit " + amount);
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
