package org.springframework.samples.ch19;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 集合注入：一次拿走全部候选。
 */
public class CollectionHolder {

	@Autowired
	private List<Greeter> greeters;

	@Autowired
	private Map<String, Greeter> greeterMap;

	/**
	 * 全部候选，按注册顺序。
	 * @return 候选列表
	 */
	public List<Greeter> greeters() {
		return this.greeters;
	}

	/**
	 * 全部候选，按 Bean 名为键。
	 * @return 候选映射
	 */
	public Map<String, Greeter> greeterMap() {
		return this.greeterMap;
	}

}
