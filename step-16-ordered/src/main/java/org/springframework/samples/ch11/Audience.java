package org.springframework.samples.ch11;

/**
 * 一个「被注入的依赖」。
 *
 * <p>
 * 它存在的唯一目的，是让 {@link GreetingService} 有一个可以标 {@code @Autowired} 的字段。
 *
 * @see org.springframework.samples.ch11.GreetingService
 */
public interface Audience {

	/**
	 * 受众的名字。
	 * @return 受众名字
	 */
	String name();

}
