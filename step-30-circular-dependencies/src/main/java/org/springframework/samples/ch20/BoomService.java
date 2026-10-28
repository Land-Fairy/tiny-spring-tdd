package org.springframework.samples.ch20;

import org.springframework.beans.factory.annotation.Value;

/**
 * 占位符没有默认值、环境里也没有：这一章要求它在启动**立刻**失败。
 */
public class BoomService {

	@Value("${app.missing}")
	private String boom;

	public String getBoom() {
		return this.boom;
	}

}
