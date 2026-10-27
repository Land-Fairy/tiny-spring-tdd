package org.springframework.samples.ch22;

import java.lang.reflect.AnnotatedElement;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;

/**
 * 永远不成立——用来演示「不满足条件时连图纸都不该留」。
 */
public class AlwaysFalseCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedElement element) {
		return false;
	}

}
