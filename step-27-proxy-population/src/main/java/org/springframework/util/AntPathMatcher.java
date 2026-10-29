package org.springframework.util;

import java.util.ArrayList;
import java.util.List;

/**
 * ant 风格路径匹配：{@code **} 跨任意层、{@code *} 只在一层之内、{@code ?} 一个字符。
 *
 * <p>
 * 核心是 {@link #doMatch} 里的<b>三段式</b>算法（和真实 Spring 同构）：
 * <ol>
 * <li>从头逐段比，遇到 {@code **} 就停</li>
 * <li>从尾逐段比，遇到 {@code **} 就停</li>
 * <li>中间剩下的是「{@code **} 夹着若干固定段落」，用贪心找位置</li>
 * </ol>
 * 为什么要分三段？因为「{@code **} 能吞多少段」是不确定的，先啃掉两端确定的，中间就只剩「找子序列」。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.util.AntPathMatcher}（1030 行，
 * 多出的大小来自：{@code {var:regex}} 模板变量、{@code extractPathWithinPattern}、 大小写开关、尾斜杠开关、以及一堆给
 * Spring MVC 用的路径抽取方法）。
 *
 * @see org.springframework.util.AntPathMatcher
 */
public class AntPathMatcher implements PathMatcher {

	/**
	 * 路径分隔符。
	 */
	public static final String DEFAULT_PATH_SEPARATOR = "/";

	private static final String SEPARATOR = DEFAULT_PATH_SEPARATOR;

	private static final String DOUBLE_WILDCARD = "**";

	@Override
	public boolean isPattern(String path) {
		return path.indexOf('*') != -1 || path.indexOf('?') != -1;
	}

	@Override
	public boolean match(String pattern, String path) {
		return doMatch(pattern, path, true);
	}

	/**
	 * 三段式匹配。
	 * @param pattern 模式
	 * @param path 待匹配路径
	 * @param fullMatch 是否要求整串匹配
	 * @return 匹配则为 {@code true}
	 */
	protected boolean doMatch(String pattern, String path, boolean fullMatch) {
		MatchCursor cursor = new MatchCursor(split(pattern), split(path));
		if (!cursor.matchFront()) {
			return false;
		}
		if (cursor.pathExhausted()) {
			return cursor.trailingSeparatorsAgree(pattern, path) || cursor.restIsAllWildcard();
		}
		if (!cursor.matchBack()) {
			return false;
		}
		if (cursor.pathExhausted()) {
			return cursor.restIsAllWildcard();
		}
		return cursor.matchMiddle();
	}

	/**
	 * 三段式匹配的游标：两端各啃掉一段确定的部分，剩下的交给 {@code **} 区间。
	 *
	 * <p>
	 * 为什么要拆成一个内部类？因为三段式要共享四个下标（模式前后 + 路径前后）， 如果全写在 {@code doMatch} 里，方法会有 70 多行——本项目
	 * checkstyle 的上限是 40 行。 <b>真实 Spring 的 {@code doMatch} 就是 150
	 * 多行的一个大方法</b>：闸门不同，形状就不同。
	 */
	private final class MatchCursor {

		private final String[] pattDirs;

		private final String[] pathDirs;

		private int pattStart;

		private int pattEnd;

		private int pathStart;

		private int pathEnd;

		MatchCursor(String[] pattDirs, String[] pathDirs) {
			this.pattDirs = pattDirs;
			this.pathDirs = pathDirs;
			this.pattEnd = pattDirs.length - 1;
			this.pathEnd = pathDirs.length - 1;
		}

		boolean matchFront() {
			while (this.pattStart <= this.pattEnd && this.pathStart <= this.pathEnd) {
				String pattDir = this.pattDirs[this.pattStart];
				if (DOUBLE_WILDCARD.equals(pattDir)) {
					return true;
				}
				if (!matchSingleSegment(pattDir, this.pathDirs[this.pathStart])) {
					return false;
				}
				this.pattStart++;
				this.pathStart++;
			}
			return true;
		}

		boolean matchBack() {
			while (this.pattStart <= this.pattEnd && this.pathStart <= this.pathEnd) {
				String pattDir = this.pattDirs[this.pattEnd];
				if (DOUBLE_WILDCARD.equals(pattDir)) {
					return true;
				}
				if (!matchSingleSegment(pattDir, this.pathDirs[this.pathEnd])) {
					return false;
				}
				this.pattEnd--;
				this.pathEnd--;
			}
			return true;
		}

		boolean matchMiddle() {
			while (this.pattStart != this.pattEnd && this.pathStart <= this.pathEnd) {
				int nextDoubleWildcard = findNextDoubleWildcard();
				if (nextDoubleWildcard == this.pattStart + 1) {
					this.pattStart++;
					continue;
				}
				int patLength = nextDoubleWildcard - this.pattStart - 1;
				int foundIdx = findSegmentRun(patLength);
				if (foundIdx == -1) {
					return false;
				}
				this.pattStart = nextDoubleWildcard;
				this.pathStart = foundIdx + patLength;
			}
			return restIsAllWildcard();
		}

		private int findNextDoubleWildcard() {
			for (int i = this.pattStart + 1; i <= this.pattEnd; i++) {
				if (DOUBLE_WILDCARD.equals(this.pattDirs[i])) {
					return i;
				}
			}
			return -1;
		}

		private int findSegmentRun(int patLength) {
			int strLength = this.pathEnd - this.pathStart + 1;
			for (int i = 0; i <= strLength - patLength; i++) {
				if (runMatchesAt(i, patLength)) {
					return this.pathStart + i;
				}
			}
			return -1;
		}

		private boolean runMatchesAt(int offset, int patLength) {
			for (int j = 0; j < patLength; j++) {
				if (!matchSingleSegment(this.pattDirs[this.pattStart + j + 1],
						this.pathDirs[this.pathStart + offset + j])) {
					return false;
				}
			}
			return true;
		}

		boolean pathExhausted() {
			return this.pathStart > this.pathEnd;
		}

		boolean restIsAllWildcard() {
			return allDoubleWildcard(this.pattDirs, this.pattStart, this.pattEnd);
		}

		boolean trailingSeparatorsAgree(String pattern, String path) {
			return this.pattStart > this.pattEnd && (pattern.endsWith(SEPARATOR) == path.endsWith(SEPARATOR));
		}

	}

	private boolean allDoubleWildcard(String[] pattDirs, int start, int end) {
		for (int i = start; i <= end; i++) {
			if (!DOUBLE_WILDCARD.equals(pattDirs[i])) {
				return false;
			}
		}
		return true;
	}

	private boolean matchSingleSegment(String pattern, String segment) {
		// 左边是快路径：完全相等，连正则都不用编。绝大多数段的比较走的是这一条。
		// 右边要先问 isPattern：没有通配符却又不相等，直接判否，省掉一次正则编译。
		return pattern.equals(segment) || (isPattern(pattern) && new AntPathStringMatcher(pattern).match(segment));
	}

	/**
	 * 按 {@code /} 切段，<b>保留</b>空段：因为 {@code a//b} 与 {@code a/b} 必须被区分开， 且 {@code /a/b}
	 * 开头的空段是「绝对路径」的标记。
	 * @param path 路径
	 * @return 段数组
	 */
	private String[] split(String path) {
		List<String> parts = new ArrayList<>();
		int start = 0;
		int idx = path.indexOf(SEPARATOR);
		while (idx != -1) {
			parts.add(path.substring(start, idx));
			start = idx + 1;
			idx = path.indexOf(SEPARATOR, start);
		}
		parts.add(path.substring(start));
		return parts.toArray(new String[0]);
	}

}
