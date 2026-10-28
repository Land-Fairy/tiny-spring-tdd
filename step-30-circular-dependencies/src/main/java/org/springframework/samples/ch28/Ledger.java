package org.springframework.samples.ch28;

import java.util.List;

/**
 * 账本：{@link #deposit(int)} 记账，{@link #appendAudit(String)} 写审计行。
 * <p>
 * 关键在实现类里：{@code deposit} 会在方法体内部调用 {@code appendAudit}， 而切点只命中 {@code appendAudit} ——
 * 这正是「自调用失效」的最小样本。
 */
public interface Ledger {

	void deposit(int amount);

	int total();

	void appendAudit(String entry);

	List<String> auditTrail();

}
