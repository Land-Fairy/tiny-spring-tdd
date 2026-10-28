package org.springframework.beans.factory;

/**
 * 由容器在<b>销毁</b>时回调的接口。
 *
 * <p>
 * 与 {@link InitializingBean} 对称：一个是「我准备好了」，一个是「我要走了」。两者都有一个共同的毛病—— 把 Bean
 * 和框架绑在一起，所以真实项目里更推荐 {@code @PostConstruct} / {@code @PreDestroy}（POJO 友好）。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.factory.DisposableBean}（46 行），方法在
 * {@code :40}。真实契约里还有一句关键的话：<b>销毁回调不应该抛异常</b>——因为此时容器正在关闭， 抛出去也没人接，只会让后面的 Bean 销毁不掉。
 *
 * @see org.springframework.beans.factory.DisposableBean
 */
public interface DisposableBean {

	/**
	 * 容器关闭时回调。
	 * @throws Exception 允许抛，但真实容器会吞掉它（见 {@code DisposableBeanAdapter.destroy()}）
	 */
	void destroy() throws Exception;

}
