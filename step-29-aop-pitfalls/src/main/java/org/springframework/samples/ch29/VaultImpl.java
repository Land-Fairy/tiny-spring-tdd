package org.springframework.samples.ch29;

public class VaultImpl implements Vault {

	@Override
	public void open() {
		this.recordAudit();
	}

	private void recordAudit() {
		// 私有方法：既不在接口里，也不可覆写 —— 任何代理技术都拦不到它。
	}

}
