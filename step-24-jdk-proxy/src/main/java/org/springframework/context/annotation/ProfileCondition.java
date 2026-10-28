package org.springframework.context.annotation;

import java.lang.reflect.AnnotatedElement;
import java.util.List;
import java.util.Set;

/**
 * {@link Profile} 背后的判定。
 *
 * <p>
 * 判定规则（与真实实现一致）：
 * <ol>
 * <li>任何一个 profile 命中激活列表 → 匹配</li>
 * <li>{@code !dev}：{@code dev} <b>没</b>激活 → 匹配</li>
 * <li>一个 {@code @Profile} 都没找到 → 匹配（没有条件就是无条件）</li>
 * </ol>
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.context.annotation.ProfileCondition}（69
 * 行）。真实版本 还会把 {@code getDefaultProfiles()} 算进来（没显式激活任何 profile 时，{@code default} 生效），
 * 本书不实现默认 profile，记在缺陷表里。
 *
 * @see Profile
 */
public class ProfileCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedElement element) {
		List<Profile> profiles = AnnotationSupport.findAnnotations(element, Profile.class);
		if (profiles.isEmpty()) {
			return true;
		}
		Set<String> activeProfiles = Set.of(context.getEnvironment().getActiveProfiles());
		for (Profile profile : profiles) {
			if (accepts(activeProfiles, profile.value())) {
				return true;
			}
		}
		return false;
	}

	private boolean accepts(Set<String> activeProfiles, String[] required) {
		for (String profile : required) {
			if (profile.startsWith("!") ? !activeProfiles.contains(profile.substring(1))
					: activeProfiles.contains(profile)) {
				return true;
			}
		}
		return false;
	}

}
