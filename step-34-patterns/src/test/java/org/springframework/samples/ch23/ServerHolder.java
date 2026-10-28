package org.springframework.samples.ch23;

import org.springframework.beans.factory.annotation.Value;

/**
 * 一个靠 {@code @Value} 完成全部初始化的 Bean：它的两个字段分别走「字符串 → int」与 「字符串 → 枚举」两条内建转换路线。
 */
public class ServerHolder {

	@Value("${port}")
	private int port;

	@Value("${level}")
	private Level level;

	public int getPort() {
		return this.port;
	}

	public Level getLevel() {
		return this.level;
	}

	@Override
	public String toString() {
		return "ServerHolder[port=" + this.port + ", level=" + this.level + "]";
	}

}
