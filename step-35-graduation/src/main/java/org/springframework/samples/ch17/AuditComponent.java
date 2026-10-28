package org.springframework.samples.ch17;

import org.springframework.context.annotation.Component;

/**
 * 最普通的被扫描组件：只标 {@code @Component}。
 */
@Component
public class AuditComponent {

	/**
	 * 返回组件名。
	 * @return 固定字符串
	 */
	public String name() {
		return "audit";
	}

}
