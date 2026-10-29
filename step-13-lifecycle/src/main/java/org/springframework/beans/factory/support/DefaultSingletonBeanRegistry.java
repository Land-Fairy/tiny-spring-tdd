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
 * <b>砍掉了什么</b>：{@code singletonFactories} / {@code earlySingletonObjects}（三级缓存， 第 30
 * 章）、{@code singletonsCurrentlyInCreation}（创建中标记，第 30 章）、 {@code disposableBeans}
 * 与依赖关系图（第 13 章）、以及 6.2 新增的 {@code tryLock} + strict/lenient 双模（第 31 章）。
 *
 * @see DefaultListableBeanFactory
 * @see org.springframework.beans.factory.support.DefaultSingletonBeanRegistry
 */
public class DefaultSingletonBeanRegistry implements SingletonBeanRegistry {

	/**
	 * 名字 → 成品。这是本章唯一新增的状态。
	 *
	 * <p>
	 * {@code 256} 是真实 Spring 的初始容量（{@code DefaultSingletonBeanRegistry.java:85}），
	 * 不是随手写的：一个中等应用的单例数量通常在这个量级，一开始就给够可以避免启动期扩容。
	 */
	private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

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
	/**
	 * <b>并发口径（六只容器一张表，别凭直觉猜）</b>：
	 * <table border="1">
	 * <caption>谁被谁保护</caption>
	 * <tr>
	 * <th>容器</th>
	 * <th>保护方式</th>
	 * <th>为什么不统一</th>
	 * </tr>
	 * <tr>
	 * <td>{@code singletonObjects}</td>
	 * <td>{@link ConcurrentHashMap}</td>
	 * <td>热路径：{@code getSingleton} 的第一次读<b>故意不进锁</b>，靠 map 自己的可见性 + 进锁后二次确认</td>
	 * </tr>
	 * <tr>
	 * <td>{@code earlySingletonObjects}</td>
	 * <td>{@link ConcurrentHashMap}</td>
	 * <td>同上，且只能并发插入；顺序无意义</td>
	 * </tr>
	 * <tr>
	 * <td>{@code singletonsCurrentlyInCreation}</td>
	 * <td>{@link ConcurrentHashMap} 的 keySet</td>
	 * <td>要原子地 {@code add} 失败即代表「已有人在造」——这正是环依赖检测的判据</td>
	 * </tr>
	 * <tr>
	 * <td>{@code singletonFactories}</td>
	 * <td>第 13 章还没有；第 30 章引入时是纯锁内 {@code HashMap}， 第 31 章提前引用路径移出创建锁后换成
	 * {@link ConcurrentHashMap}</td>
	 * <td>保护方式跟着「读写是否都在锁内」走，不是跟着字段名字走</td>
	 * </tr>
	 * <tr>
	 * <td>{@code registeredSingletons}</td>
	 * <td>{@code Collections.synchronizedSet}</td>
	 * <td>要<b>保序</b>（{@link LinkedHashSet}），而 JDK 没有并发的保序 Set</td>
	 * </tr>
	 * <tr>
	 * <td>{@code disposableBeans}</td>
	 * <td>{@code Collections.synchronizedMap}</td>
	 * <td>要保序，且写者不总在 {@code singletonLock} 里（销毁路径只对 map 自身加锁取快照）</td>
	 * </tr>
	 * </table>
	 * <p>
	 * 这张表是<b>判定结果</b>而不是风格：每个字段的容器类型都能用「这个访问点是否已在锁内」和「是否需要保序」两问推出来。 反过来说，看到
	 * {@code Collections.synchronizedXxx} 就想「统一成 ConcurrentHashMap」是错的—— 会同时丢掉保序并让
	 * {@code destroySingletons()} 的快照失去原子性。
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
	}

	/**
	 * 从池子里取，取不到返回 {@code null}。不做创建。
	 * @param beanName Bean 的名字
	 * @return 池子里的对象，或 {@code null}
	 */
	@Override
	public Object getSingleton(String beanName) {
		return this.singletonObjects.get(beanName);
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
				singletonObject = singletonFactory.getObject();
				addSingleton(beanName, singletonObject);
			}
			return singletonObject;
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
