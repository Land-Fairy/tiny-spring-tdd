package org.springframework.beans;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.springframework.util.ClassUtils;

/**
 * {@link BeanWrapper} 的实现：用 JDK 自带的<b>内省</b>找到 setter，再用反射调用它。
 *
 * <p>
 * <b>为什么用 {@code Introspector} 而不是自己扫 {@code getMethods()}</b>： {@code Introspector}
 * 已经把「{@code setPrefix} ↔ 属性名 {@code prefix}」这条命名约定、 继承链、{@code getX}/{@code isX}
 * 的配对都处理好了。自己扫方法名要重新发现一遍 JavaBeans 规范， 而且 {@code URL}、{@code Class}
 * 之类的参数类型会被解释成嵌套属性，容易踩坑。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.BeanWrapperImpl}（311 行）+
 * {@code AbstractNestablePropertyAccessor}（1104 行）。真实实现<b>也</b>基于 {@code Introspector}，
 * 只是套了一层 {@code CachedIntrospectionResults} 缓存（每个类只内省一次），
 * 并且支持嵌套属性（{@code foo.bar.baz}）、索引属性（{@code list[0]}）、类型转换（第 23 章）。
 *
 * @see org.springframework.beans.BeanWrapperImpl
 */
public class BeanWrapperImpl implements BeanWrapper {

	private final Object wrappedInstance;

	private final Class<?> wrappedClass;

	private final Map<String, PropertyDescriptor> propertyDescriptors = new HashMap<>();

	/**
	 * 包住一个已存在的对象。
	 * @param instance 目标实例
	 */
	public BeanWrapperImpl(Object instance) {
		this.wrappedInstance = instance;
		this.wrappedClass = instance.getClass();
		try {
			for (PropertyDescriptor descriptor : Introspector.getBeanInfo(this.wrappedClass).getPropertyDescriptors()) {
				this.propertyDescriptors.put(descriptor.getName(), descriptor);
			}
		}
		catch (IntrospectionException ex) {
			throw new IllegalStateException("Failed to introspect bean class [" + this.wrappedClass.getName() + "]",
					ex);
		}
	}

	@Override
	public Object getWrappedInstance() {
		return this.wrappedInstance;
	}

	@Override
	public Class<?> getWrappedClass() {
		return this.wrappedClass;
	}

	@Override
	public void setPropertyValue(PropertyValue propertyValue) {
		String propertyName = propertyValue.getName();
		PropertyDescriptor descriptor = this.propertyDescriptors.get(propertyName);
		if (descriptor == null || descriptor.getWriteMethod() == null) {
			throw new NotWritablePropertyException(propertyName, this.wrappedClass);
		}

		Method setter = descriptor.getWriteMethod();
		Class<?> requiredType = setter.getParameterTypes()[0];
		Object value = propertyValue.getValue();
		checkType(propertyName, value, requiredType);

		try {
			setter.invoke(this.wrappedInstance, value);
		}
		catch (InvocationTargetException ex) {
			// setter 自己抛了异常：真正有用的信息在 cause 里，不在 "invocation failed" 里。
			throw new IllegalStateException("Failed to invoke setter of property '" + propertyName + "' on bean class ["
					+ this.wrappedClass.getName() + "]", ex.getTargetException());
		}
		catch (IllegalAccessException ex) {
			throw new IllegalStateException("Failed to access setter of property '" + propertyName + "' on bean class ["
					+ this.wrappedClass.getName() + "]", ex);
		}
	}

	@Override
	public void setPropertyValues(PropertyValues propertyValues) {
		for (PropertyValue propertyValue : propertyValues.getPropertyValues()) {
			setPropertyValue(propertyValue);
		}
	}

	private void checkType(String propertyName, Object value, Class<?> requiredType) {
		if (value == null) {
			// null 不是「某个类型的值」：引用类型可以接受，基本类型没有 null 这个取值，
			// 硬塞进 setter 只会被拆箱成 0 而静默失真。
			if (requiredType.isPrimitive()) {
				throw new TypeMismatchException(propertyName, null, requiredType, this.wrappedClass);
			}
			return;
		}
		if (!ClassUtils.isAssignable(requiredType, value.getClass())) {
			throw new TypeMismatchException(propertyName, value, requiredType, this.wrappedClass);
		}
	}

}
