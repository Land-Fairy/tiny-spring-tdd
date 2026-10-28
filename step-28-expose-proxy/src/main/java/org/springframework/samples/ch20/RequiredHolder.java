package org.springframework.samples.ch20;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 必需依赖：同样的缺失，这次必须让启动失败。
 */
public class RequiredHolder {

	@Autowired
	private MissingCollaborator collaborator;

	public MissingCollaborator getCollaborator() {
		return this.collaborator;
	}

}
