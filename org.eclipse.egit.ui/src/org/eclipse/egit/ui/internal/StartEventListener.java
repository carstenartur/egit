/*******************************************************************************
 * Copyright (c) 2021 Thomas Wolf <thomas.wolf@paranor.ch> and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.ui.internal;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.ui.workbench.UIEvents;
import org.eclipse.egit.core.JobFamilies;
import org.eclipse.egit.ui.Activator;
import org.eclipse.egit.ui.internal.selection.SelectionRepositoryStateCache;
import org.eclipse.egit.ui.internal.variables.GitTemplateVariableResolver;
import org.eclipse.jdt.core.manipulation.JavaManipulation;
import org.eclipse.jface.text.templates.TemplateContextType;
import org.eclipse.swt.widgets.Display;
import org.eclipse.text.templates.ContextTypeRegistry;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.progress.IProgressService;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;

/**
 * Initializes some EGit components that rely on the workbench having been
 * created.
 */
@Component(property = EventConstants.EVENT_TOPIC + '='
		+ UIEvents.UILifeCycle.APP_STARTUP_COMPLETE)
public class StartEventListener implements EventHandler {

	private final AtomicBoolean started = new AtomicBoolean();

	private void startInternalComponents() {
		if (started.compareAndSet(false, true)) {
			SelectionRepositoryStateCache.INSTANCE.initialize();
			registerCoreJobFamilyIcons();
			registerTemplateVariableResolvers();
		}
	}

	@Override
	public void handleEvent(Event event) {
		if (UIEvents.UILifeCycle.APP_STARTUP_COMPLETE
				.equals(event.getTopic())) {
			startInternalComponents();
		}
	}

	@Deactivate
	void shutDown() {
		if (started.get()) {
			SelectionRepositoryStateCache.INSTANCE.dispose();
		}
	}

	private void runAsync(Runnable action) {
		Display display = PlatformUI.getWorkbench().getDisplay();
		if (display != null && !display.isDisposed()) {
			display.asyncExec(() -> {
				if (!display.isDisposed() && PlatformUI.isWorkbenchRunning()) {
					action.run();
				}
			});
		}
	}

	private void registerCoreJobFamilyIcons() {
		runAsync(() -> {
			IProgressService service = PlatformUI.getWorkbench()
					.getProgressService();
			if (service == null) {
				return;
			}
			service.registerIconForFamily(UIIcons.PULL, JobFamilies.PULL);
			service.registerIconForFamily(UIIcons.REPOSITORY,
					JobFamilies.AUTO_IGNORE);
			service.registerIconForFamily(UIIcons.REPOSITORY,
					JobFamilies.AUTO_SHARE);
			service.registerIconForFamily(UIIcons.REPOSITORY,
					JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			service.registerIconForFamily(UIIcons.REPOSITORY,
					JobFamilies.REPOSITORY_CHANGED);
		});
	}

	private void registerTemplateVariableResolvers() {
		Bundle javaUi = Platform.getBundle("org.eclipse.jdt.ui"); //$NON-NLS-1$
		if (javaUi == null) {
			return;
		}
		runAsync(() -> {
			try {
				registerTemplateVariableResolvers(javaUi);
			} catch (Throwable e) {
				// while catching Throwable is an anti-pattern, we may
				// experience NoClassDefFoundErrors here
				Activator.logError(
						"Cannot register git support for Java templates", //$NON-NLS-1$
						e);
			}
		});
	}

	/**
	 * Registers the resolvers after JDT UI has initialized the public registry.
	 *
	 * @param javaUi
	 *            the installed JDT UI bundle
	 * @throws BundleException
	 *             if the JDT UI bundle cannot be activated
	 * @throws IllegalStateException
	 *             if JDT UI did not initialize its code template registry
	 */
	static void registerTemplateVariableResolvers(Bundle javaUi)
			throws BundleException {
		// The public getter does not activate JDT UI or initialize its registry.
		// Do not change the bundle's persistent autostart setting.
		javaUi.start(Bundle.START_TRANSIENT);
		ContextTypeRegistry codeTemplateContextRegistry = JavaManipulation
				.getCodeTemplateContextRegistry();
		if (codeTemplateContextRegistry == null) {
			throw new IllegalStateException(
					"JDT UI did not initialize the code template context registry"); //$NON-NLS-1$
		}
		Iterator<TemplateContextType> ctIter = codeTemplateContextRegistry
				.contextTypes();

		while (ctIter.hasNext()) {
			TemplateContextType contextType = ctIter
					.next();
			contextType.addResolver(new GitTemplateVariableResolver(
					"git_config", //$NON-NLS-1$
					UIText.GitTemplateVariableResolver_GitConfigDescription));
		}
	}
}
