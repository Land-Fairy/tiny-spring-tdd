package org.springframework.beans;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PropertyValues：一批「属性名 → 值」的有序容器")
class MutablePropertyValuesTests {

	@Test
	@DisplayName("默认是空的")
	void isEmptyByDefault() {
		MutablePropertyValues pvs = new MutablePropertyValues();

		assertThat(pvs.isEmpty()).isTrue();
		assertThat(pvs.size()).isZero();
	}

	@Test
	@DisplayName("按加入顺序保存，不排序、不去重")
	void keepsInsertionOrder() {
		MutablePropertyValues pvs = new MutablePropertyValues().add("prefix", "Hi, ")
			.add(new PropertyValue("repeat", 3));

		PropertyValue[] values = pvs.getPropertyValues();

		assertThat(values).hasSize(2);
		assertThat(values[0].getName()).isEqualTo("prefix");
		assertThat(values[0].getValue()).isEqualTo("Hi, ");
		assertThat(values[1].getName()).isEqualTo("repeat");
		assertThat(values[1].getValue()).isEqualTo(3);
	}

	@Test
	@DisplayName("允许值为 null：能不能填是容器该判断的事，不是属性清单该判断的事")
	void allowsNullValue() {
		MutablePropertyValues pvs = new MutablePropertyValues().add("prefix", null);

		assertThat(pvs.isEmpty()).isFalse();
		assertThat(pvs.getPropertyValueList().get(0).getValue()).isNull();
	}

	@Test
	@DisplayName("同一个属性名加两次，两份都在（覆盖是后话，第 08 章才处理合并）")
	void duplicateNamesAreKept() {
		MutablePropertyValues pvs = new MutablePropertyValues().add("repeat", 1).add("repeat", 5);

		assertThat(pvs.size()).isEqualTo(2);
	}

}
