package org.springframework.beans.factory.support;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * 直接调 {@code Constructor#newInstance} 的实现：没有字节码增强，没有子类。
 *
 * <p>
 * <b>为什么需要它</b>：本章要把「反射调用失败」翻译成一句人话。{@code newInstance} 会抛出六种异常 （见本章正文），其中
 * {@code InvocationTargetException} 最坑——它包着<b>构造器自己</b>抛的那个异常， 不拆开就会看到「invocation
 * failed」而不知道真实原因是「构造器里抛了 IllegalStateException」。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.support.SimpleInstantiationStrategy}
 * （205 行），它的 {@code instantiate} 只做一件事：{@code ctor.newInstance(args)}，把
 * {@code InvocationTargetException} 交给上层翻译成 {@code BeanInstantiationException}。 它的子类
 * {@code CglibSubclassingInstantiationStrategy} 才是默认策略——而那是本书刻意砍掉的部分 （ADR-0002）。
 *
 * @see org.springframework.beans.factory.support.SimpleInstantiationStrategy
 */
public class SimpleInstantiationStrategy implements InstantiationStrategy {

	@Override
	public Object instantiate(Constructor<?> constructor, Object... args) {
		try {
			return constructor.newInstance(args);
		}
		catch (InstantiationException ex) {
			throw new IllegalStateException(
					"Failed to instantiate [" + constructor.getDeclaringClass().getName() + "]: class is abstract", ex);
		}
		catch (IllegalAccessException ex) {
			throw new IllegalStateException("Failed to instantiate [" + constructor.getDeclaringClass().getName()
					+ "]: constructor is not accessible", ex);
		}
		catch (InvocationTargetException ex) {
			throw new IllegalStateException("Failed to instantiate [" + constructor.getDeclaringClass().getName()
					+ "]: constructor threw [" + ex.getTargetException() + "]", ex.getTargetException());
		}
	}

}
