package org.strangeforest.tcb.stats.spring;

import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.*;

/**
 * Defaults for filter controls, configurable without code changes.
 * Templates read them as {@code ${@uiDefaults.activePlayersByDefault}}.
 */
@Component("uiDefaults")
public class UIDefaults {

	private final boolean activePlayersByDefault;

	public UIDefaults(@Value("${tennis-stats.ui.active-players-by-default:true}") boolean activePlayersByDefault) {
		this.activePlayersByDefault = activePlayersByDefault;
	}

	/** Whether "Active players" filters start ticked (lists, leaders, performance and record tables). */
	public boolean isActivePlayersByDefault() {
		return activePlayersByDefault;
	}
}
