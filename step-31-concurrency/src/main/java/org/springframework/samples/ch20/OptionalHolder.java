package org.springframework.samples.ch20;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 可选依赖：容器里没有 {@link MissingCollaborator} 的实现，字段保持 {@code null}。
 */
public class OptionalHolder {

	@Autowired(required = false)
	private MissingCollaborator collaborator;

	public boolean hasCollaborator() {
		return this.collaborator != null;
	}

	public MissingCollaborator getCollaborator() {
		return this.collaborator;
	}

}
