/*******************************************************************************
 * Copyright (c) 2026 Carsten Hammer and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.ui.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.egit.ui.internal.variables.GitTemplateVariableResolver;
import org.eclipse.jdt.core.manipulation.JavaManipulation;
import org.eclipse.jface.text.templates.TemplateBuffer;
import org.eclipse.jface.text.templates.TemplateContext;
import org.eclipse.jface.text.templates.TemplateContextType;
import org.eclipse.jface.text.templates.TemplateTranslator;
import org.eclipse.jface.text.templates.TemplateVariableResolver;
import org.eclipse.jgit.api.Git;
import org.eclipse.text.templates.ContextTypeRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;

public class StartEventListenerTest {

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void registersResolversAfterActivatingJdtUi() throws Exception {
		Bundle javaUi = mock(Bundle.class);
		AtomicBoolean activated = new AtomicBoolean();
		doAnswer(invocation -> {
			activated.set(true);
			return null;
		}).when(javaUi).start(Bundle.START_TRANSIENT);

		ContextTypeRegistry registry = new ContextTypeRegistry();
		TemplateContextType first = new TemplateContextType("first");
		TemplateContextType second = new TemplateContextType("second");
		TemplateVariableResolver existing = new TemplateVariableResolver("existing", "existing");
		first.addResolver(existing);
		registry.addContextType(first);
		registry.addContextType(second);

		// Static mocks are thread-scoped. Never replace JDT's process-wide registry.
		try (MockedStatic<JavaManipulation> manipulation = mockStatic(JavaManipulation.class)) {
			manipulation.when(JavaManipulation::getCodeTemplateContextRegistry).thenAnswer(invocation -> {
				assertTrue("Registry read before JDT UI activation", activated.get());
				return registry;
			});
			StartEventListener.registerTemplateVariableResolvers(javaUi);
			assertTrue(findResolver(first, "git_config") instanceof GitTemplateVariableResolver);
			assertTrue(findResolver(second, "git_config") instanceof GitTemplateVariableResolver);
			assertSame(existing, findResolver(first, "existing"));
			assertEquals(2, resolverCount(first));
			assertEquals(1, resolverCount(second));
			verify(javaUi).start(Bundle.START_TRANSIENT);
		}
	}

	@Test
	public void propagatesActivationFailureWithoutReadingRegistry() throws Exception {
		Bundle javaUi = mock(Bundle.class);
		BundleException failure = new BundleException("Cannot activate JDT UI");
		doThrow(failure).when(javaUi).start(Bundle.START_TRANSIENT);
		try (MockedStatic<JavaManipulation> manipulation = mockStatic(JavaManipulation.class)) {
			assertSame(failure, assertThrows(BundleException.class,
					() -> StartEventListener.registerTemplateVariableResolvers(javaUi)));
			manipulation.verifyNoInteractions();
		}
	}

	@Test
	public void reportsMissingRegistryInsteadOfSilentlySkippingRegistration() throws Exception {
		Bundle javaUi = mock(Bundle.class);
		try (MockedStatic<JavaManipulation> manipulation = mockStatic(JavaManipulation.class)) {
			manipulation.when(JavaManipulation::getCodeTemplateContextRegistry).thenReturn(null);
			assertThrows(IllegalStateException.class,
					() -> StartEventListener.registerTemplateVariableResolvers(javaUi));
			verify(javaUi).start(Bundle.START_TRANSIENT);
		}
	}

	@Test
	public void registeredResolverExpandsRepositoryConfiguration() throws Exception {
		ContextTypeRegistry registry = new ContextTypeRegistry();
		TemplateContextType type = new TemplateContextType("code-template");
		registry.addContextType(type);
		IProject project = mock(IProject.class);
		TemplateContext context = mock(TemplateContext.class,
				withSettings().extraInterfaces(IAdaptable.class));
		when(((IAdaptable) context).getAdapter(IProject.class)).thenReturn(project);
		RepositoryMapping mapping = mock(RepositoryMapping.class);

		try (Git git = Git.init().setDirectory(temporaryFolder.newFolder("repository")).call();
				MockedStatic<JavaManipulation> manipulation = mockStatic(JavaManipulation.class);
				MockedStatic<RepositoryMapping> mappings = mockStatic(RepositoryMapping.class)) {
			git.getRepository().getConfig().setString("user", null, "name", "Template Author");
			git.getRepository().getConfig().save();
			when(mapping.getRepository()).thenReturn(git.getRepository());
			mappings.when(() -> RepositoryMapping.getMapping(project)).thenReturn(mapping);
			manipulation.when(JavaManipulation::getCodeTemplateContextRegistry).thenReturn(registry);

			StartEventListener.registerTemplateVariableResolvers(mock(Bundle.class));
			assertNotNull(findResolver(type, "git_config"));
			TemplateBuffer buffer = new TemplateTranslator().translate("${author:git_config(user.name)}");
			type.resolve(buffer, context);
			assertEquals("Template Author", buffer.getString());
		}
	}

	private static TemplateVariableResolver findResolver(TemplateContextType context, String type) {
		for (Iterator<TemplateVariableResolver> it = context.resolvers(); it.hasNext();) {
			TemplateVariableResolver resolver = it.next();
			if (type.equals(resolver.getType())) {
				return resolver;
			}
		}
		return null;
	}

	private static int resolverCount(TemplateContextType context) {
		int count = 0;
		for (Iterator<TemplateVariableResolver> it = context.resolvers(); it.hasNext();) {
			it.next();
			count++;
		}
		return count;
	}
}
