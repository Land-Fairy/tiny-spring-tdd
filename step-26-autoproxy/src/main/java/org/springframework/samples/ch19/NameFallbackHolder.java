package org.springframework.samples.ch19;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 字段名恰好等于 Bean 名：决策链的最后一步靠它兜底。
 */
public class NameFallbackHolder {

	@Autowired
	private Repository userRepository;

	/**
	 * 被注入的仓库。
	 * @return 仓库
	 */
	public Repository repository() {
		return this.userRepository;
	}

}
