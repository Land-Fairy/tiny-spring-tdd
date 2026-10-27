package org.springframework.samples.ch22;

import java.lang.reflect.AnnotatedElement;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;

/**
 * 永远成立。
 */
public class AlwaysTrueCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedElement element) {
		return true;
	}

}
