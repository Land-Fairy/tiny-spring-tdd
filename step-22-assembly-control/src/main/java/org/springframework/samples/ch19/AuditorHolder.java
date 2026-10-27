package org.springframework.samples.ch19;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 注入一个没有任何实现的接口：预期报「无候选」。
 */
public class AuditorHolder {

	@Autowired
	private Auditor auditor;

}
