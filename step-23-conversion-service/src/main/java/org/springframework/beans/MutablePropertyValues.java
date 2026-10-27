package org.springframework.beans;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link PropertyValues} 的可写实现：一个<b>有序、允许重复</b>的属性指令列表。
 *
 * <p>
 * <b>为什么是 List 不是 Map</b>：这是本章最值得停下来看的一处设计。直觉上「属性名 → 值」就是 Map， 但真实 Spring 用的是 List，原因有两个：
 * <ol>
 * <li><b>顺序会被观察</b>：setter 之间可能有依赖（先设 {@code dataSource} 再设 {@code jdbcTemplate}），
 * 填错顺序会炸；Map 的迭代顺序（{@code HashMap} 时代）不可依赖，就是第 01 章演示过的那件事。</li>
 * <li><b>重复不是错误，是需要合并的信息</b>：父子图纸、{@code @Bean} 与 XML 都可能给同一个属性赋值，
 * 「谁覆盖谁」要留给合并阶段判断，不能在数据结构里悄悄丢掉一条。</li>
 * </ol>
 *
 * @see org.springframework.beans.MutablePropertyValues
 */
public class MutablePropertyValues implements PropertyValues {

	private final List<PropertyValue> propertyValueList = new ArrayList<>();

	/**
	 * 追加一条属性指令。
	 * @param name 属性名
	 * @param value 要填入的值
	 * @return 当前对象，便于链式调用
	 */
	public MutablePropertyValues add(String name, Object value) {
		this.propertyValueList.add(new PropertyValue(name, value));
		return this;
	}

	/**
	 * 追加一条已经封装好的属性指令。
	 * @param propertyValue 属性指令
	 * @return 当前对象，便于链式调用
	 */
	public MutablePropertyValues add(PropertyValue propertyValue) {
		this.propertyValueList.add(propertyValue);
		return this;
	}

	/**
	 * 可写的内部列表（真实 Spring 也提供了这个「后门」，用于合并与去重）。
	 * @return 可修改的属性指令列表
	 */
	public List<PropertyValue> getPropertyValueList() {
		return this.propertyValueList;
	}

	/**
	 * 属性指令条数。
	 * @return 条数
	 */
	public int size() {
		return this.propertyValueList.size();
	}

	@Override
	public PropertyValue[] getPropertyValues() {
		return this.propertyValueList.toArray(new PropertyValue[0]);
	}

	@Override
	public boolean isEmpty() {
		return this.propertyValueList.isEmpty();
	}

}
