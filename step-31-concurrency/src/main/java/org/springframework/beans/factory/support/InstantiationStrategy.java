package org.springframework.beans.factory.support;

import java.lang.reflect.Constructor;

/**
 * 「参数已经齐了，把对象 {@code new} 出来」这一步。
 *
 * <p>
 * <b>为什么要把这么小的一步单独抽出来</b>：因为「选哪个构造器、每个参数填什么」和「怎么把它 new 出来」
 * 是两件会各自演化的事。前者要处理候选排序、类型匹配、异常合并（本章的 {@link ConstructorResolver}）； 后者在有 CGLIB
 * 之后会变成「用字节码生成子类再实例化」（真实 Spring 的
 * {@code CglibSubclassingInstantiationStrategy}）。混在一个方法里，两边的复杂度会互相纠缠。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.support.InstantiationStrategy}， 有三个重载的
 * {@code instantiate}（无参、指定构造器、指定构造器加参数）。本书只留最后一个。
 *
 * @see SimpleInstantiationStrategy
 * @see ConstructorResolver
 * @see org.springframework.beans.factory.support.InstantiationStrategy
 */
public interface InstantiationStrategy {

	/**
	 * 用给定构造器和参数造一个对象。
	 * @param constructor 已选定的构造器
	 * @param args 已经解析好的参数，顺序与构造器声明一致
	 * @return 新对象
	 */
	Object instantiate(Constructor<?> constructor, Object... args);

}
