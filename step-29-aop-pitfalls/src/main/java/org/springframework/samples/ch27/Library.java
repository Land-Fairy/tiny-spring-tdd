package org.springframework.samples.ch27;

/**
 * 被切面罩住的业务接口。
 * <p>
 * 注意这里的 {@code floor} 属性<b>故意不放进接口</b>：接口是代理能长出来的唯一形状，接口里没有的东西代理上就没有。
 */
public interface Library {

	String getTitle();

	void setTitle(String title);

	String describe();

}
