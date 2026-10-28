package org.springframework.samples.ch17.service;

import org.springframework.context.annotation.Service;

/**
 * 子包里的 {@code @Service}：用来证明扫描是<b>递归</b>的。
 */
@Service
public class SmsService {

	/**
	 * 假装发短信。
	 * @param phone 手机号
	 * @return 固定字符串
	 */
	public String send(String phone) {
		return "sent to " + phone;
	}

}
