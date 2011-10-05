/*******************************************************************************
 * Copyright (c) 2011, IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.equinox.http.servlet.internal;

import java.lang.reflect.*;
import java.util.HashSet;
import java.util.Set;
import javax.servlet.ServletContext;

class ServletContextProxyFactory extends Object {
	private static final String PROXY_METHOD_TRACING_PROPERTY = "org.eclipse.equinox.http.servlet.internal.proxy.method.tracing"; //$NON-NLS-1$
	private static final boolean PROXY_METHOD_TRACING = Boolean.getBoolean(ServletContextProxyFactory.PROXY_METHOD_TRACING_PROPERTY);

	private static class MethodAdvisor extends Object {
		private final Class subject;
		private final Set methodsCache;
		private final boolean methodCacheEnabled;

		// Property to turn off method caching.
		private static final String DISABLE_METHOD_CACHE = "org.eclipse.equinox.http.servlet.internal.disable.method.cache"; //$NON-NLS-1$

		private static boolean isMethodCacheEnabled() {
			return Boolean.getBoolean(MethodAdvisor.DISABLE_METHOD_CACHE) == false;
		}

		MethodAdvisor(Class subject) {
			super();
			if (subject == null)
				throw new IllegalArgumentException("subject must not be null"); //$NON-NLS-1$
			if (subject.isInterface())
				throw new IllegalArgumentException("subject must not be an interface"); //$NON-NLS-1$
			this.subject = subject;
			this.methodsCache = new HashSet(17);
			this.methodCacheEnabled = MethodAdvisor.isMethodCacheEnabled();
		}

		private boolean hasValidModifiers(Method declaredMethod) {
			int modifiers = declaredMethod.getModifiers();
			boolean valid;
			valid = Modifier.isPublic(modifiers);
			if (valid == false)
				return false;
			valid = Modifier.isAbstract(modifiers) == false;
			if (valid == false)
				return false;
			return true;
		}

		private boolean isImplemented(Class clazz, Method method) {
			if (clazz == null)
				return false;
			Method[] declaredMethods = clazz.getDeclaredMethods();
			for (int i = 0; i < declaredMethods.length; i++) {
				Method declaredMethod = declaredMethods[i];
				boolean valid = hasValidModifiers(declaredMethod);
				if (valid == false)
					continue;
				boolean match = method.equals(declaredMethod);
				if (match == false)
					continue;
				methodsCache.add(method);
				return true; // Implemented and added to cache.
			}
			Class parent = clazz.getSuperclass();
			return isImplemented(parent, method);
		}

		boolean isImplemented(Method method) {
			if (method == null)
				throw new IllegalArgumentException("method must not be null"); //$NON-NLS-1$
			synchronized (methodsCache) {
				if (methodCacheEnabled) {
					boolean exists = methodsCache.contains(method);
					if (exists)
						return true; // Implemented and exists in cache.
				}
				return isImplemented(subject, method);
			}
		}
	}

	private MethodAdvisor methodAdvisor;

	ServletContextProxyFactory() {
		super();
		this.methodAdvisor = new MethodAdvisor(ServletContextAdaptor.class);
	}

	ServletContext create(ServletContextAdaptor adapter) {
		if (adapter == null)
			throw new IllegalArgumentException("adapter must not be null"); //$NON-NLS-1$
		ClassLoader loader = ServletContextAdaptor.class.getClassLoader();
		InvocationHandler handler = createServletContextInvocationHandler(adapter);
		Class[] interfaces = new Class[] {ServletContext.class};
		return (ServletContext) Proxy.newProxyInstance(loader, interfaces, handler);
	}

	private InvocationHandler createServletContextInvocationHandler(final ServletContextAdaptor adapter) {
		return new InvocationHandler() {
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
				return ServletContextProxyFactory.this.invoke(adapter, proxy, method, args);
			}
		};
	}

	private Object invoke(ServletContextAdaptor adapter, Object proxy, Method method, Object[] args) throws Throwable {
		if (ServletContextProxyFactory.PROXY_METHOD_TRACING) {
			System.out.println("TRACE-invoking: " + method.getName()); //$NON-NLS-1$
		}
		boolean match = methodAdvisor.isImplemented(method);
		Object object = match ? adapter : adapter.getSubject();
		return method.invoke(object, args);
	}
}
