package org.springframework.beans.factory.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.beans.factory.DisposableBean;

import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.SingletonBeanRegistry;

/**
 * 单例池：名字 → 已经造好的对象。
 *
 * <p>
 * <b>为什么需要它</b>：单例的语义只有一句——「同一个名字，永远还你同一个对象」。但只有一张表是不够的，
 * 因为「检查有没有」和「没有就造一个放进去」是<b>两步</b>。两个线程同时走这两步，就会各造一个，
 * 后写的覆盖先写的，于是「单例」这个名字下面曾经存在过两个对象，其中一个已经泄露给调用者了。
 *
 * <p>
 * <b>本章的核心一行</b>：把<b>创建动作</b>包成 {@link ObjectFactory} 交给本类， 由本类在锁内决定「造不造」。加锁的范围必须覆盖「判空 +
 * 创建 + 放回」整体，而不能只包住其中某一步—— 这是本章最容易做错的地方。
 *
 * <p>
 * <b>锁的两层</b>：
 * <ol>
 * <li><b>快路径</b>：先在锁外读一次 {@code singletonObjects}（{@link ConcurrentHashMap} 的读不需要加锁）。
 * 绝大多数 {@code getBean} 都命中这一层，代价接近一次哈希查找。
 * <li><b>慢路径</b>：读不到才抢锁，进锁之后<b>再读一遍</b>（双重检查）；仍没有才真的创建。
 * </ol>
 * 少了第二次检查，第一个拿到锁的线程造完退出后，第二个线程会拿着它抢锁之前读到的 {@code null} 再造一个。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.support.DefaultSingletonBeanRegistry}
 * （841 行）。它的锁字段是 {@code final Lock singletonLock = new ReentrantLock();}
 * （{@code DefaultSingletonBeanRegistry.java:82}），{@code singletonObjects} 是
 * {@code new ConcurrentHashMap<>(256)}（{@code :85}）。
 *
 * <p>
 * <b>砍掉了什么</b>：6.2 的 {@code singletonCallbacks}（创建回调）、{@code inCreationCheckExclusions}
 * （创建中检查豁免）、lenient 创建模式（{@code lenientCreationLock} /
 * {@code singletonsInLenientCreation} / {@code currentCreationThreads}，为兼容老代码而死锁规避用），
 * 以及依赖关系图三兄弟 {@code containedBeanMap} / {@code dependentBeanMap} /
 * {@code dependenciesForBeanMap}（真实 Spring {@code :91-:137}）。这几样在本书的场景里都用不到，
 * 硬塞进来只会让「三级缓存」这条主线变糊。
 *
 * @see DefaultListableBeanFactory
 */
public class DefaultSingletonBeanRegistry implements SingletonBeanRegistry {

	/**
	 * 名字 → 成品。它是三级缓存的<b>第一级</b>：成品。第二级（提前引用）与第三级（{@code ObjectFactory}）由<b>第 30
	 * 章</b>加入，把第三级换成并发容器由<b>第 31 章</b>完成。
	 *
	 * <p>
	 * {@code 256} 是真实 Spring 的初始容量（{@code DefaultSingletonBeanRegistry.java:85}），
	 * 不是随手写的：一个中等应用的单例数量通常在这个量级，一开始就给够可以避免启动期扩容。
	 */
	private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

	/**
	 * 正在创建中的单例名字。<b>第 30 章</b>。
	 *
	 * <p>
	 * 它解决的不是「环能不能解」，而是「环出现了我能不能<b>说清楚</b>」。第 29 章场景五里我们的容器 直接栈溢出：{@code doCreateBean}
	 * 一层层自己调自己，1024 层之后 JVM 放弃。加上这个集合之后， 同一个环会变成一句点名到 Bean 的异常。
	 *
	 * <p>
	 * 真实 Spring 的同一行在 {@code DefaultSingletonBeanRegistry.java:89}，用的也是
	 * {@code ConcurrentHashMap.newKeySet(16)}——「判断在不在」这个语义只需要 Set，不需要值。
	 */
	private final Set<String> singletonsCurrentlyInCreation = ConcurrentHashMap.newKeySet(16);

	/**
	 * 二级缓存：提前暴露出去的对象。<b>第 30 章</b>。
	 *
	 * <p>
	 * 它存在的唯一理由是<b>同一个提前引用只该被创建一次</b>：第三级缓存里那个工厂每次调用都会跑一遍
	 * （对代理来说就是再造一个代理），所以第一次取到结果后就该落到这里，之后的调用都读这里。
	 *
	 * <p>
	 * 真实 Spring 的同一行在 {@code DefaultSingletonBeanRegistry.java:88}。
	 */
	private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);

	/**
	 * 三级缓存：提前引用的<b>工厂</b>。<b>第 30 章</b>。
	 *
	 * <p>
	 * 它与二级缓存的唯一差别是「什么时候求值」：二级缓存存的是<b>已经算好</b>的对象，三级缓存存的是
	 * <b>还算得出来的办法</b>。这个「延迟」正是代理场景的分水岭——只有等到「真的有人来要提前引用」时，
	 * 我们才知道该交裸对象还是交代理（{@code getEarlyBeanReference} 也只有那时才该被调用）。
	 *
	 * <p>
	 * <b>第 31 章</b>把这里从 {@code HashMap} 换成了 {@link ConcurrentHashMap}：上一章它是纯锁内的，
	 * 而现在提前引用路径不再进创建锁，{@code remove} 要在锁外被别人看见。真实 Spring 6.2 的同一行
	 * （{@code DefaultSingletonBeanRegistry.java:88}）也是 {@code ConcurrentHashMap}——原因完全相同。
	 */
	private final Map<String, ObjectFactory<?>> singletonFactories = new ConcurrentHashMap<>(16);

	/**
	 * 所有进过池子的名字，按注册顺序保存。
	 *
	 * <p>
	 * 它和 {@code singletonObjects} 的键集看起来重复，但用途不同：{@link LinkedHashSet} 保序， 而
	 * {@code ConcurrentHashMap} 的迭代顺序是哈希顺序。真实 Spring 用它在 {@code getSingletonNames()}
	 * 里给出一份<b>顺序稳定</b>的快照 （{@code DefaultSingletonBeanRegistry.java:97} 声明、{@code :499}
	 * 使用）， 这样排查问题时日志里的 Bean 顺序才是可复现的。
	 */
	private final Set<String> registeredSingletons = Collections.synchronizedSet(new LinkedHashSet<>(256));

	/**
	 * 单例锁。真实 Spring 的同一行在 {@code DefaultSingletonBeanRegistry.java:82}。
	 *
	 * <p>
	 * 为什么用 {@link ReentrantLock} 而不是 {@code synchronized (singletonObjects)}？ 因为真实 Spring
	 * 需要 {@code tryLock}（拿不到就放弃，避免死等），以及把锁暴露给子类
	 * （{@code SingletonBeanRegistry#getSingletonMutex()}）。本章用不到 {@code tryLock}，
	 * 但把字段类型先对齐，第 30、31 章才能在同一行上继续加戏。
	 */
	private final Lock singletonLock = new ReentrantLock();

	/**
	 * 需要销毁回调的 Bean，按<b>注册顺序</b>保存。
	 *
	 * <p>
	 * 为什么必须是保序的 {@link LinkedHashMap}？因为销毁要按<b>逆序</b>执行（后创建的先销毁）， 而
	 * {@code ConcurrentHashMap} 的迭代顺序是哈希顺序——那样销毁顺序就成了随机的。 真实 Spring 用的是
	 * {@code Map<String, Object> disposableBeans = new LinkedHashMap<>()}
	 * （{@code DefaultSingletonBeanRegistry.java:128}），并在 {@code destroySingletons()}
	 * 里倒着遍历。
	 */
	private final Map<String, DisposableBean> disposableBeans = Collections.synchronizedMap(new LinkedHashMap<>());

	/**
	 * 直接把一个已经造好的对象放进池子。
	 * @param beanName Bean 的名字
	 * @param singletonObject 对象
	 */
	@Override
	public void registerSingleton(String beanName, Object singletonObject) {
		if (beanName == null) {
			throw new IllegalArgumentException("Bean name must not be null");
		}
		if (singletonObject == null) {
			throw new IllegalArgumentException("Singleton object must not be null");
		}
		this.singletonLock.lock();
		try {
			addSingleton(beanName, singletonObject);
		}
		finally {
			this.singletonLock.unlock();
		}
	}

	/**
	 * 往池子里放一个对象。同名已有对象则报错，而不是静默覆盖。
	 *
	 * <p>
	 * {@code putIfAbsent} 一次完成「查 + 写」，中间没有窗口——这一点很关键： 如果先用 {@code containsKey} 判断再
	 * {@code put}，两次操作之间别的线程可能刚好写进去了。
	 * @param beanName Bean 的名字
	 * @param singletonObject 对象
	 */
	protected void addSingleton(String beanName, Object singletonObject) {
		Object oldObject = this.singletonObjects.putIfAbsent(beanName, singletonObject);
		if (oldObject != null) {
			throw new IllegalStateException("Could not register object [" + singletonObject + "] under bean name '"
					+ beanName + "': there is already object [" + oldObject + "] bound");
		}
		this.registeredSingletons.add(beanName);
		// 二、三级缓存里的痕迹必须一起清掉，否则下一次解析依赖还能拿到「已经被替换掉的」早期对象。
		this.earlySingletonObjects.remove(beanName);
		this.singletonFactories.remove(beanName);
	}

	/**
	 * 从池子里取，取不到返回 {@code null}。不做创建。
	 * @param beanName Bean 的名字
	 * @return 池子里的对象，或 {@code null}
	 */
	@Override
	public Object getSingleton(String beanName) {
		return getSingleton(beanName, true);
	}

	/**
	 * 池子里没有时，再看一级「提前暴露」。
	 * <p>
	 * {@code allowEarlyReference} 存在的理由很具体：<b>只有解析依赖时才该拿提前引用</b>。像
	 * {@code getBeanNamesForType} 这类「我只是想看一眼有哪些 Bean」的查询必须传 {@code false}，
	 * 否则一次类型扫描就会把半成品对象提前造出来。
	 * @param beanName Bean 名字
	 * @param allowEarlyReference 允许返回还没初始化完的对象吗
	 * @return 单例、提前引用，或 {@code null}
	 */
	protected Object getSingleton(String beanName, boolean allowEarlyReference) {
		Object singletonObject = this.singletonObjects.get(beanName);
		if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
			singletonObject = this.earlySingletonObjects.get(beanName);
			if (singletonObject == null && allowEarlyReference) {
				// 不进创建锁：提前引用只该由「正在创建它的线程」推导，别的线程无权排队，
				// 更不该为了一个自己无权推导的东西去等。第 30 章曾把这个「等」说成死锁源，
				// 本章更正：全类只有一把可重入锁，等待图成不了环——它只是把并发变成串行。
				// 求值资格由一次原子操作裁决：remove 只有一个线程能拿到非 null 的工厂，
				// 拿到它就是拿到「令牌」，没拿到的线程不重复求值。
				// 令牌制留下两对已知后果，都写在这里而不藏着：①没拿到令牌的线程可能读到 null
				// （对方尚未 put），于是它自己走「创建」那条路；②拿到令牌的线程求值期间，对象可能
				// 已经完工并 addSingleton（同时清掉了二/三级缓存），随后这次求值再把旧提前引用
				// put 回二级缓存——留下一张永不过期的旧图纸。它读不到（一级缓存已命中，
				// 且 isSingletonCurrentlyInCreation 已为 false），代价是一处内存残留。
				ObjectFactory<?> singletonFactory = this.singletonFactories.remove(beanName);
				if (singletonFactory != null) {
					singletonObject = singletonFactory.getObject();
					this.earlySingletonObjects.put(beanName, singletonObject);
				}
				else {
					// 令牌被别的线程抢走了：它正在求值，或者已经求完。读二级缓存看结果。
					singletonObject = this.earlySingletonObjects.get(beanName);
				}
			}
		}
		return singletonObject;
	}

	/**
	 * 核心方法：池子里有就还给你，没有才调工厂造一个并放回池子。
	 *
	 * <p>
	 * 三个必须连在一起的动作：<b>判空 → 创建 → 放回</b>。它们必须在同一个锁内完成， 否则「单例」这个词就是假的。
	 * @param beanName Bean 的名字
	 * @param singletonFactory 创建动作的延迟包装
	 * @return 池子里的对象（可能是刚造出来的）
	 */
	public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
		if (beanName == null) {
			throw new IllegalArgumentException("Bean name must not be null");
		}
		if (singletonFactory == null) {
			throw new IllegalArgumentException("Singleton factory must not be null");
		}
		// 快路径：绝大多数调用命中这里，不抢锁。
		Object singletonObject = this.singletonObjects.get(beanName);
		if (singletonObject != null) {
			return singletonObject;
		}
		this.singletonLock.lock();
		try {
			// 慢路径：进锁之后再读一遍。少了这一遍，第一个线程造完退出后，
			// 第二个线程会拿着它抢锁之前读到的 null 再造一个。
			singletonObject = this.singletonObjects.get(beanName);
			if (singletonObject == null) {
				beforeSingletonCreation(beanName);
				boolean created = false;
				try {
					singletonObject = singletonFactory.getObject();
					created = true;
				}
				finally {
					// 无论成功还是异常，标记都必须摘掉：否则一个创建失败的 Bean 会永远「在创建中」，
					// 之后每一次合法创建它都会被误判成环。
					afterSingletonCreation(beanName);
				}
				if (created) {
					addSingleton(beanName, singletonObject);
				}
			}
			return singletonObject;
		}
		finally {
			this.singletonLock.unlock();
		}
	}

	/**
	 * 登记「我要开始造这个单例了」。同名已在创建中 → 环。
	 * <p>
	 * 真实 Spring 在 {@code beforeSingletonCreation}（{@code :542}）里只做一件事： 往集合里加名字，加不进去就抛异常。
	 * @param beanName Bean 名字
	 */
	protected void beforeSingletonCreation(String beanName) {
		if (!this.singletonsCurrentlyInCreation.add(beanName)) {
			throw new BeanCurrentlyInCreationException(beanName);
		}
	}

	/**
	 * 登记「这个单例造完了（或造失败了）」。
	 * @param beanName Bean 名字
	 */
	protected void afterSingletonCreation(String beanName) {
		this.singletonsCurrentlyInCreation.remove(beanName);
	}

	/**
	 * 这个 Bean 现在是不是正在被创建？
	 * @param beanName Bean 名字
	 * @return 在创建中返回 {@code true}
	 */
	public boolean isSingletonCurrentlyInCreation(String beanName) {
		return this.singletonsCurrentlyInCreation.contains(beanName);
	}

	/**
	 * 登记「提前暴露」用的工厂。<b>第 30 章</b>。
	 *
	 * <p>
	 * 注意它登记的<b>不是对象</b>，而是一个「随时可以问一次」的工厂——这是第三级缓存唯一特别的地方。 真实 Spring 的同一处在 {@code :192}。
	 * @param beanName Bean 名字
	 * @param singletonFactory 返回「可提前暴露的对象」的工厂
	 */
	protected void addSingletonFactory(String beanName, ObjectFactory<?> singletonFactory) {
		if (beanName == null) {
			throw new IllegalArgumentException("Bean name must not be null");
		}
		if (singletonFactory == null) {
			throw new IllegalArgumentException("Singleton factory must not be null");
		}
		this.singletonLock.lock();
		try {
			// 只登记「怎么算」，不算。这是三级缓存与两级缓存的全部差别。
			this.singletonFactories.put(beanName, singletonFactory);
			this.earlySingletonObjects.remove(beanName);
		}
		finally {
			this.singletonLock.unlock();
		}
	}

	/**
	 * 把某个对象从池子里摘掉（图纸不受影响）。
	 * @param beanName Bean 的名字
	 * @return 被摘掉的对象，本来就没有则 {@code null}
	 */
	protected Object removeSingleton(String beanName) {
		Object removed = this.singletonObjects.remove(beanName);
		this.registeredSingletons.remove(beanName);
		this.earlySingletonObjects.remove(beanName);
		this.singletonFactories.remove(beanName);
		this.disposableBeans.remove(beanName);
		return removed;
	}

	/**
	 * 登记一个「需要销毁回调」的 Bean。
	 *
	 * <p>
	 * 注意它登记的是<b>适配器</b>而不是 Bean 本身（{@link DisposableBeanAdapter}）：池子只认一个接口，
	 * 三种销毁通道由适配器统一。真实 Spring 同款（{@code registerDisposableBean}）。
	 * @param beanName Bean 名字
	 * @param disposableBean 销毁适配器
	 */
	protected void registerDisposableBean(String beanName, DisposableBean disposableBean) {
		this.disposableBeans.put(beanName, disposableBean);
	}

	@Override
	public boolean containsSingleton(String beanName) {
		return this.singletonObjects.containsKey(beanName);
	}

	@Override
	public String[] getSingletonNames() {
		synchronized (this.registeredSingletons) {
			return this.registeredSingletons.toArray(new String[0]);
		}
	}

	@Override
	public int getSingletonCount() {
		// 真实 Spring 数的是 registeredSingletons（DefaultSingletonBeanRegistry.java:504），
		// 而不是 singletonObjects：它统计的是「登记过几个名字」，名字的顺序与来源都在同一个集合里。
		return this.registeredSingletons.size();
	}

	/**
	 * 销毁所有单例。
	 *
	 * <p>
	 * 两步，顺序不能反：先把<b>登记过销毁回调</b>的名字抄一份（逆序），逐个销毁；最后清空两个池子。 为什么先抄一份再遍历？因为销毁过程中可能有代码又去
	 * {@code getBean}（真实 Spring 6.2 特意允许 「关闭期宽松获取」），直接边遍历边改会破坏迭代器。
	 *
	 * <p>
	 * <b>为什么逆序</b>：后创建的对象往往依赖先创建的对象（第 05 章的注入顺序），所以后创建的要先销毁。 真实 Spring
	 * 同样是倒着遍历（{@code DefaultSingletonBeanRegistry.java:706-708}）。
	 */
	@Override
	public void destroySingletons() {
		List<String> disposableBeanNames;
		synchronized (this.disposableBeans) {
			disposableBeanNames = new ArrayList<>(this.disposableBeans.keySet());
		}
		for (int i = disposableBeanNames.size() - 1; i >= 0; i--) {
			destroySingleton(disposableBeanNames.get(i));
		}
		this.registeredSingletons.clear();
		this.singletonObjects.clear();
	}

	/**
	 * 销毁单个单例：先调销毁回调，再摘掉登记。
	 * @param beanName Bean 名字
	 */
	public void destroySingleton(String beanName) {
		DisposableBean disposableBean;
		synchronized (this.disposableBeans) {
			disposableBean = this.disposableBeans.remove(beanName);
		}
		if (disposableBean != null) {
			try {
				disposableBean.destroy();
			}
			catch (Exception ignored) {
				// 销毁期吞异常：此时容器正在关闭，抛出去只会让后面的 Bean 销毁不掉。
				// 适配器内部已经吞过一遍，这里是兜底（真实 Spring 捕 Throwable，本书只捕 Exception，见适配器注释）。
			}
		}
		removeSingleton(beanName);
	}

}
