/*******************************************************************************
 * Copyright (c) 2005, 2011 Cognos Incorporated, IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Cognos Incorporated - initial API and implementation
 *     IBM Corporation - bug fixes and enhancements
 *******************************************************************************/
package org.eclipse.equinox.jsp.jasper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.Permission;
import java.security.PermissionCollection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.equinox.internal.jsp.jasper.JspClassLoader;
import org.osgi.framework.Bundle;

/**
 * <p>
 * JSPServlet wraps the Apache Jasper Servlet making it appropriate for running in an OSGi environment under the Http Service.
 * The Jasper JSPServlet makes use of the Thread Context Classloader to support compile and runtime of JSPs and to accommodate running
 * in an OSGi environment, a Bundle is used to provide the similar context normally provided by the webapp.
 * </p>
 * <p>
 *  The Jasper Servlet will search the ServletContext to find JSPs, tag library descriptors, and additional information in the web.xml
 *  as per the JSP 2.0 specification. In addition to the ServletContext this implementation will search the bundle (but not attached
 *  fragments) for matching resources in a manner consistent with the Http Service's notion of a resource. By using alias and bundleResourcePath the JSP lookup should be in 
 *  line with the resource mapping specified in {102.4} of the OSGi HttpService.
 *  </p>
 *  <p>
 *  TLD discovery is slightly different, to clarify it occurs in one of three ways:
 *  <ol>
 *  <li> declarations found in /WEB-INF/web.xml (found either on the bundleResourcePath in the bundle or in the ServletContext)</li>
 *  <li> tld files found under /WEB-INF (found either on the bundleResourcePath in the bundle or in the ServletContext)</li>
 *  <li> tld files found in jars on the Bundle-Classpath (see org.eclipse.equinox.internal.jsp.jasper.JSPClassLoader)</li>
 *  </ol>
 *  </p>
 *  <p>
 *  Other than the setting and resetting of the thread context classloader and additional resource lookups in the bundle the JSPServlet
 *  is behaviourally consistent with the JSP 2.0 specification and regular Jasper operation.
 *  </p>
 * @noextend This class is not intended to be subclassed by clients.
 */

public class JspServlet extends HttpServlet {

	private static class BundlePermissionCollection extends PermissionCollection {
		private static final long serialVersionUID = -6365478608043900677L;
		private Bundle bundle;

		public BundlePermissionCollection(Bundle bundle) {
			this.bundle = bundle;
			super.setReadOnly();
		}

		public void add(Permission permission) {
			throw new SecurityException();
		}

		public boolean implies(Permission permission) {
			return bundle.hasPermission(permission);
		}

		public Enumeration elements() {
			return Collections.enumeration(Collections.EMPTY_LIST);
		}
	}

	private static final long serialVersionUID = -4110476909131707652L;
	private Servlet jspServlet = new org.apache.jasper.servlet.JspServlet();
	Bundle bundle;
	private URLClassLoader jspLoader;
	String bundleResourcePath;
	String alias;

	public JspServlet(Bundle bundle, String bundleResourcePath, String alias) {
		this.bundle = bundle;
		this.bundleResourcePath = (bundleResourcePath == null || bundleResourcePath.equals("/")) ? "" : bundleResourcePath; //$NON-NLS-1$ //$NON-NLS-2$
		this.alias = (alias == null || alias.equals("/")) ? null : alias; //$NON-NLS-1$
		jspLoader = new JspClassLoader(bundle);
	}

	public JspServlet(Bundle bundle, String bundleResourcePath) {
		this(bundle, bundleResourcePath, null);
	}

	public void init(ServletConfig config) throws ServletException {
		ClassLoader original = Thread.currentThread().getContextClassLoader();
		try {
			Thread.currentThread().setContextClassLoader(jspLoader);
			jspServlet.init(new ServletConfigAdaptor(config));

			// If a SecurityManager is set we need to override the permissions collection set in Jasper's JSPRuntimeContext
			if (System.getSecurityManager() != null) {
				try {
					Field jspRuntimeContextField = jspServlet.getClass().getDeclaredField("rctxt"); //$NON-NLS-1$
					jspRuntimeContextField.setAccessible(true);
					Object jspRuntimeContext = jspRuntimeContextField.get(jspServlet);
					Field permissionCollectionField = jspRuntimeContext.getClass().getDeclaredField("permissionCollection"); //$NON-NLS-1$
					permissionCollectionField.setAccessible(true);
					permissionCollectionField.set(jspRuntimeContext, new BundlePermissionCollection(bundle));
				} catch (Exception e) {
					throw new ServletException("Cannot initialize JSPServlet. Failed to set JSPRuntimeContext permission collection."); //$NON-NLS-1$
				}
			}
		} finally {
			Thread.currentThread().setContextClassLoader(original);
		}
	}

	public void destroy() {
		ClassLoader original = Thread.currentThread().getContextClassLoader();
		try {
			Thread.currentThread().setContextClassLoader(jspLoader);
			jspServlet.destroy();
		} finally {
			Thread.currentThread().setContextClassLoader(original);
		}
	}

	public void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String pathInfo = request.getPathInfo();
		if (pathInfo != null && pathInfo.startsWith("/WEB-INF/")) { //$NON-NLS-1$
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		ClassLoader original = Thread.currentThread().getContextClassLoader();
		try {
			Thread.currentThread().setContextClassLoader(jspLoader);
			jspServlet.service(request, response);
		} finally {
			Thread.currentThread().setContextClassLoader(original);
		}
	}

	public ServletConfig getServletConfig() {
		return jspServlet.getServletConfig();
	}

	public String getServletInfo() {
		return jspServlet.getServletInfo();
	}

	private class ServletConfigAdaptor implements ServletConfig {
		private ServletConfig config;
		private ServletContext context;

		public ServletConfigAdaptor(ServletConfig config) {
			this.config = config;
			this.context = createServletContext();
		}

		private ServletContext createServletContext() {
			ServletContext servletContext = config.getServletContext();
			ServletContextAdaptor adapter = new ServletContextAdaptor(servletContext);
			ServletContextProxyFactory factory = new ServletContextProxyFactory();
			return factory.create(adapter);
		}

		public String getInitParameter(String arg0) {
			return config.getInitParameter(arg0);
		}

		public Enumeration getInitParameterNames() {
			return config.getInitParameterNames();
		}

		public ServletContext getServletContext() {
			return context;
		}

		public String getServletName() {
			return config.getServletName();
		}
	}

	private class ServletContextAdaptor implements ServletContext {
		private ServletContext delegate;

		public ServletContextAdaptor(ServletContext delegate) {
			this.delegate = delegate;
		}

		public URL getResource(String name) throws MalformedURLException {
			if (alias != null && name.startsWith(alias))
				name = name.substring(alias.length());

			String resourceName = bundleResourcePath + name;
			int lastSlash = resourceName.lastIndexOf('/');
			if (lastSlash == -1)
				return null;

			String path = resourceName.substring(0, lastSlash);
			if (path.length() == 0)
				path = "/"; //$NON-NLS-1$
			String file = sanitizeEntryName(resourceName.substring(lastSlash + 1));
			Enumeration entryPaths = bundle.findEntries(path, file, false);
			if (entryPaths != null && entryPaths.hasMoreElements())
				return (URL) entryPaths.nextElement();

			return delegate.getResource(name);
		}

		private String sanitizeEntryName(String name) {
			StringBuffer buffer = null;
			for (int i = 0; i < name.length(); i++) {
				char c = name.charAt(i);
				switch (c) {
					case '*' :
					case '\\' :
						// we need to escape '*' and '\'
						if (buffer == null) {
							buffer = new StringBuffer(name.length() + 16);
							buffer.append(name.substring(0, i));
						}
						buffer.append('\\').append(c);
						break;
					default :
						if (buffer != null)
							buffer.append(c);
						break;
				}
			}
			return (buffer == null) ? name : buffer.toString();
		}

		public InputStream getResourceAsStream(String name) {
			try {
				URL resourceURL = getResource(name);
				if (resourceURL != null)
					return resourceURL.openStream();
			} catch (IOException e) {
				log("Error opening stream for resource '" + name + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
			}
			return null;
		}

		public Set getResourcePaths(String name) {
			Set result = delegate.getResourcePaths(name);
			Enumeration e = bundle.findEntries(bundleResourcePath + name, null, false);
			if (e != null) {
				if (result == null)
					result = new HashSet();
				while (e.hasMoreElements()) {
					URL entryURL = (URL) e.nextElement();
					result.add(entryURL.getFile().substring(bundleResourcePath.length()));
				}
			}
			return result;
		}

		public RequestDispatcher getRequestDispatcher(String arg0) {
			return delegate.getRequestDispatcher(arg0);
		}

		public Object getAttribute(String arg0) {
			return delegate.getAttribute(arg0);
		}

		public Enumeration getAttributeNames() {
			return delegate.getAttributeNames();
		}

		public ServletContext getContext(String arg0) {
			return delegate.getContext(arg0);
		}

		public String getInitParameter(String arg0) {
			return delegate.getInitParameter(arg0);
		}

		public Enumeration getInitParameterNames() {
			return delegate.getInitParameterNames();
		}

		public int getMajorVersion() {
			return delegate.getMajorVersion();
		}

		public String getMimeType(String arg0) {
			return delegate.getMimeType(arg0);
		}

		public int getMinorVersion() {
			return delegate.getMinorVersion();
		}

		public RequestDispatcher getNamedDispatcher(String arg0) {
			return delegate.getNamedDispatcher(arg0);
		}

		public String getRealPath(String arg0) {
			return delegate.getRealPath(arg0);
		}

		public String getServerInfo() {
			return delegate.getServerInfo();
		}

		/** @deprecated **/
		public Servlet getServlet(String arg0) throws ServletException {
			return delegate.getServlet(arg0);
		}

		public String getServletContextName() {
			return delegate.getServletContextName();
		}

		/** @deprecated **/
		public Enumeration getServletNames() {
			return delegate.getServletNames();
		}

		/** @deprecated **/
		public Enumeration getServlets() {
			return delegate.getServlets();
		}

		/** @deprecated **/
		public void log(Exception arg0, String arg1) {
			delegate.log(arg0, arg1);
		}

		public void log(String arg0, Throwable arg1) {
			delegate.log(arg0, arg1);
		}

		public void log(String arg0) {
			delegate.log(arg0);
		}

		public void removeAttribute(String arg0) {
			delegate.removeAttribute(arg0);
		}

		public void setAttribute(String arg0, Object arg1) {
			delegate.setAttribute(arg0, arg1);
		}

		// Added in Servlet 2.5
		public String getContextPath() {
			try {
				Method getContextPathMethod = delegate.getClass().getMethod("getContextPath", null); //$NON-NLS-1$
				return (String) getContextPathMethod.invoke(delegate, null);
			} catch (Exception e) {
				// ignore
			}
			return null;
		}

		Object getSubject() {
			return delegate;
		}
	}

	static class ServletContextProxyFactory extends Object {
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
}
