package org.springframework.context.support;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.Scope;

/**
 * 每个线程一个实例的作用域。
 *
 * <p>
 * 它证明了作用域的全部机制：一张 {@code ThreadLocal} 包着的 {@code Map}， 外加一个「对象被移除时跑什么」的回调表。
 *
 * <p>
 * 真实对应物：{@code org.springframework.context.support.SimpleThreadScope}（98 行）， 它用
 * {@code NamedThreadLocal} 包一层只为调试时能看到名字。 真实 Spring 默认<b>不注册</b>它——要用得自己
 * {@code beanFactory.registerScope("thread", new SimpleThreadScope())}。
 */
public class SimpleThreadScope implements Scope {

	private final ThreadLocal<Map<String, Object>> threadScope = ThreadLocal.withInitial(HashMap::new);

	private final ThreadLocal<Map<String, Runnable>> destructionCallbacks = ThreadLocal.withInitial(HashMap::new);

	@Override
	public Object get(String name, ObjectFactory<?> objectFactory) {
		Map<String, Object> scope = this.threadScope.get();
		Object object = scope.get(name);
		if (object == null) {
			object = objectFactory.getObject();
			scope.put(name, object);
		}
		return object;
	}

	@Override
	public Object remove(String name) {
		Runnable callback = this.destructionCallbacks.get().remove(name);
		if (callback != null) {
			callback.run();
		}
		return this.threadScope.get().remove(name);
	}

	@Override
	public void registerDestructionCallback(String name, Runnable callback) {
		this.destructionCallbacks.get().put(name, callback);
	}

	@Override
	public String getConversationId() {
		return Thread.currentThread().getName();
	}

}
