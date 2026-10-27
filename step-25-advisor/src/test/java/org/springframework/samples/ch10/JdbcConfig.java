package org.springframework.samples.ch10;

/**
 * 本章的样本 Bean：三个属性全部来自外部配置。
 *
 * <p>
 * 注意三个属性的类型都是 {@code String}。这不是偷懒——本书还没有类型转换服务（第 23 章），{@code BeanWrapperImpl}
 * 只会检查「类型是否兼容」，不会把 {@code "4"} 变成 {@code 4}。 真实 Spring 在这里会自动转换，所以本章刻意不写 {@code int}
 * 属性，避免把一个真实的限制藏起来。
 */
public class JdbcConfig {

	private String url;

	private String user;

	private String ddl;

	public String getUrl() {
		return this.url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getUser() {
		return this.user;
	}

	public void setUser(String user) {
		this.user = user;
	}

	public String getDdl() {
		return this.ddl;
	}

	public void setDdl(String ddl) {
		this.ddl = ddl;
	}

}
