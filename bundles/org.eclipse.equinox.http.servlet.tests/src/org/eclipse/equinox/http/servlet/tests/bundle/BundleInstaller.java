/*******************************************************************************
 * Copyright (c) 2006, 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.http.servlet.tests.bundle;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.eclipse.osgi.service.urlconversion.URLConverter;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.util.tracker.ServiceTracker;

public class BundleInstaller {
	private BundleContext context;
	private String rootLocation;
	private HashMap bundles = new HashMap();
	private ServiceTracker converter;

	public BundleInstaller(String bundlesRoot, BundleContext context) throws InvalidSyntaxException {
		this.context = context;
		rootLocation = bundlesRoot;
		converter = new ServiceTracker(context, context.createFilter("(&(objectClass=" + URLConverter.class.getName() + ")(protocol=bundleentry))"), null);
		converter.open();
	}

	synchronized public Bundle installBundle(String name) throws BundleException {
		return installBundle(name, true);
	}

	synchronized public Bundle installBundle(String name, boolean track) throws BundleException {
		if (bundles == null && track)
			return null;
		String location = getBundleLocation(name);
		Bundle bundle = context.installBundle(location);
		if (track)
			bundles.put(name, bundle);
		return bundle;
	}

	public String getBundleLocation(String name) throws BundleException {
		String bundleFileName = rootLocation + "/" + name;
		URL bundleURL = context.getBundle().getEntry(bundleFileName);
		if (bundleURL == null)
			bundleURL = context.getBundle().getEntry(bundleFileName + ".jar");
		if (bundleURL == null)
			throw new BundleException("Could not find bundle to install at: " + name);
		try {
			bundleURL = ((URLConverter) converter.getService()).resolve(bundleURL);
		} catch (IOException e) {
			throw new BundleException("Converter error", e);
		}
		String location = bundleURL.toExternalForm();
		if ("file".equals(bundleURL.getProtocol()))
			location = "reference:" + location;
		return location;
	}

	synchronized public Bundle updateBundle(String fromName, String toName) throws BundleException {
		if (bundles == null)
			return null;
		Bundle fromBundle = (Bundle) bundles.get(fromName);
		if (fromBundle == null)
			throw new BundleException("The bundle to update does not exist!! " + fromName);
		String bundleFileName = rootLocation + "/" + toName;
		URL bundleURL = context.getBundle().getEntry(bundleFileName);
		if (bundleURL == null)
			bundleURL = context.getBundle().getEntry(bundleFileName + ".jar");
		try {
			bundleURL = ((URLConverter) converter.getService()).resolve(bundleURL);
		} catch (IOException e) {
			throw new BundleException("Converter error", e);
		}
		String location = bundleURL.toExternalForm();
		if ("file".equals(bundleURL.getProtocol()))
			location = "reference:" + location;
		try {
			fromBundle.update(new URL(location).openStream());
		} catch (Exception e) {
			throw new BundleException("Errors when updating bundle " + fromBundle, e);
		}
		bundles.remove(fromName);
		bundles.put(toName, fromBundle);
		return fromBundle;
	}

	synchronized public Bundle uninstallBundle(String name) throws BundleException {
		if (bundles == null)
			return null;
		Bundle bundle = (Bundle) bundles.remove(name);
		if (bundle == null)
			return null;
		bundle.uninstall();
		return bundle;
	}
	
	synchronized public void uninstallBundle(Bundle b) throws BundleException {
		if (bundles == null)
			return;
		if (bundles.containsValue(b)) {
			for (Iterator it = bundles.entrySet().iterator(); it.hasNext();) {
				Map.Entry entry = (Map.Entry) it.next();
				if (entry.getValue().equals(b)) {
					bundles.remove(entry.getKey());
					break;
				}
			}
		}
		b.uninstall();
	}

	synchronized public Bundle[] uninstallAllBundles() throws BundleException {
		if (bundles == null)
			return null;
		ArrayList result = new ArrayList(bundles.size());
		for (Iterator iter = bundles.values().iterator(); iter.hasNext();) {
			Bundle bundle = (Bundle) iter.next();
			try {
				bundle.uninstall();
			} catch (IllegalStateException e) {
				// ignore; bundle probably already uninstalled
			}
			result.add(bundle);
		}
		bundles.clear();
		return (Bundle[]) result.toArray(new Bundle[result.size()]);
	}

	synchronized public Bundle getBundle(String name) {
		if (bundles == null)
			return null;
		return (Bundle) bundles.get(name);
	}
	
	synchronized public Bundle[] shutdown() throws BundleException {
		if (bundles == null)
			return null;
		Bundle[] result = uninstallAllBundles();
		converter.close();
		bundles = null;
		return result;
	}
}
