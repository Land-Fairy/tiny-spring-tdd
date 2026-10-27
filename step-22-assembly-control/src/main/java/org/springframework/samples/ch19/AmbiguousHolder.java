package org.springframework.samples.ch19;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 字段名不匹配任何 Bean 名，且没有任何消歧标记：预期报错。
 */
public class AmbiguousHolder {

	@Autowired
	private Repository repository;

}
