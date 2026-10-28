package org.springframework.aop;

/**
 * 所有「增强」的根标记接口。
 * <p>
 * 它自己没有一个方法 —— 因为增强的种类差别太大：前置增强要知道"调之前做什么"，环绕增强要能"决定调不调"， 它们的共同点只是"能被挂到连接点上"。真实 Spring 的
 * {@code Advice}（spring-aop，34 行）同样是空接口， 并派生
 * {@code BeforeAdvice}、{@code AfterAdvice}、{@code Interceptor} 三条子线。
 *
 * @see org.aopalliance.aop.Advice
 */
public interface Advice {

}
