package org.springframework.core;

/**
 * 「我不但有个排序值，而且我属于<b>第一批</b>」。
 *
 * <p>
 * 它是个空接口——一个纯粹的<b>标记</b>。标记的意义全在比较器里： 两组之间<b>根本不比数值</b>，{@code PriorityOrdered} 整组排在
 * {@code Ordered} 整组之前。
 *
 * <p>
 * 为什么需要这个「粗暴」的分组？因为有些扩展点必须在<b>所有</b>普通扩展点之前跑， 而它们的实现者无法预知别人的数值。举例：真实 Spring 里
 * {@code ConfigurationClassPostProcessor}（处理 {@code @Configuration}）是
 * {@code PriorityOrdered}， 因为所有 {@code @Bean} 方法都得先被解析成图纸，别的后处理器才可能看到那些图纸。 如果它只是
 * {@code Ordered}，任何一个写了 {@code getOrder() = 0} 的第三方扩展点都可能抢在它前面， 于是「图纸还没生成」——这类 bug
 * 几乎无法从堆栈看出来。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.PriorityOrdered}（47 行，纯标记接口）。
 *
 * @see org.springframework.core.PriorityOrdered
 */
public interface PriorityOrdered extends Ordered {

}
