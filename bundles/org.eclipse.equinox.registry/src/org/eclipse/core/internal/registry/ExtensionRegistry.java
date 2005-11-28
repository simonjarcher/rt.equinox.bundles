/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.internal.registry;

import java.io.*;
import java.lang.reflect.Array;
import java.util.*;
import javax.xml.parsers.ParserConfigurationException;
import org.eclipse.core.internal.registry.spi.ExtensionDescription;
import org.eclipse.core.internal.registry.spi.ExtensionProperty;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.adaptor.FileManager;
import org.eclipse.equinox.registry.*;
import org.eclipse.equinox.registry.spi.RegistryStrategy;
import org.eclipse.osgi.util.NLS;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * An implementation for the extension registry API.
 */
public class ExtensionRegistry implements IExtensionRegistry {

	protected class ListenerInfo {
		public String filter;
		public EventListener listener;

		public ListenerInfo(EventListener listener, String filter) {
			this.listener = listener;
			this.filter = filter;
		}

		/**
		 * Used by ListenerList to ensure uniqueness.
		 */
		public boolean equals(Object another) {
			return another instanceof ListenerInfo && ((ListenerInfo) another).listener == this.listener;
		}
	}

	// used to enforce concurrent access policy for readers/writers
	private ReadWriteMonitor access = new ReadWriteMonitor();

	// deltas not broadcasted yet. Deltas are kept organized by the namespace name (objects with the same namespace are grouped together)
	private transient Map deltas = new HashMap(11);

	//file manager associated with the registry cache
	protected FileManager cacheFileManager;

	// all registry change listeners
	private transient ListenerList listeners = new ListenerList();

	private RegistryObjectManager registryObjects = null;

	// set to "true" if registry was able to use cache to populate it's content. 
	// if "false", content is empty and might need to be filled in
	protected boolean isRegistryFilledFromCache = false;

	// Table reader associated with this extension registry
	protected TableReader theTableReader = new TableReader(this);

	// The "key" to the object. 
	private Object token;

	/////////////////////////////////////////////////////////////////////////////////////////
	// Registry strategies
	protected RegistryStrategy strategy;
	protected ICompatibilityStrategy compatibilityStrategy = null;

	public RegistryObjectManager getObjectManager() {
		return registryObjects;
	}

	/**
	 * Sets new cache file manager. If existing file manager was owned by the registry,
	 * closes it.
	 *  
	 * @param newFileManager - new cache file manager
	 * @param registryOwnsManager - true: life cycle of the file manager is controlled by the registry
	 */
	protected void setFileManager(File cacheBase, boolean isCacheReadOnly) {
		if (cacheFileManager != null)
			cacheFileManager.close(); // close existing file manager first

		if (cacheBase != null) {
			cacheFileManager = new FileManager(cacheBase, isCacheReadOnly ? "none" : null, isCacheReadOnly); //$NON-NLS-1$
			try {
				cacheFileManager.open(!isCacheReadOnly);
			} catch (IOException e) {
				// Ignore the exception. The registry will be rebuilt from source.
			}
		}
	}

	/**
	 * Adds and resolves all extensions and extension points provided by the
	 * plug-in.
	 * <p>
	 * A corresponding IRegistryChangeEvent will be broadcast to all listeners
	 * interested on changes in the given plug-in.
	 * </p>
	 */
	public void add(Contribution element) {
		access.enterWrite();
		try {
			basicAdd(element, true);
			fireRegistryChangeEvent();
		} finally {
			access.exitWrite();
		}
	}

	public void add(Contribution[] elements) {
		access.enterWrite();
		try {
			for (int i = 0; i < elements.length; i++)
				basicAdd(elements[i], true);
			fireRegistryChangeEvent();
		} finally {
			access.exitWrite();
		}
	}

	/* Utility method to help with array concatenations */
	static Object concatArrays(Object a, Object b) {
		Object[] result = (Object[]) Array.newInstance(a.getClass().getComponentType(), Array.getLength(a) + Array.getLength(b));
		System.arraycopy(a, 0, result, 0, Array.getLength(a));
		System.arraycopy(b, 0, result, Array.getLength(a), Array.getLength(b));
		return result;
	}

	private String addExtension(int extension) {
		Extension addedExtension = (Extension) registryObjects.getObject(extension, RegistryObjectManager.EXTENSION);
		String extensionPointToAddTo = addedExtension.getExtensionPointIdentifier();
		ExtensionPoint extPoint = registryObjects.getExtensionPointObject(extensionPointToAddTo);
		//orphan extension
		if (extPoint == null) {
			registryObjects.addOrphan(extensionPointToAddTo, extension);
			return null;
		}
		// otherwise, link them
		int[] newExtensions;
		int[] existingExtensions = extPoint.getRawChildren();
		newExtensions = new int[existingExtensions.length + 1];
		System.arraycopy(existingExtensions, 0, newExtensions, 0, existingExtensions.length);
		newExtensions[newExtensions.length - 1] = extension;
		link(extPoint, newExtensions);
		return recordChange(extPoint, extension, IExtensionDelta.ADDED);
	}

	/**
	 * Looks for existing orphan extensions to connect to the given extension
	 * point. If none is found, there is nothing to do. Otherwise, link them.
	 */
	private String addExtensionPoint(int extPoint) {
		ExtensionPoint extensionPoint = (ExtensionPoint) registryObjects.getObject(extPoint, RegistryObjectManager.EXTENSION_POINT);
		int[] orphans = registryObjects.removeOrphans(extensionPoint.getUniqueIdentifier());
		if (orphans == null)
			return null;
		link(extensionPoint, orphans);
		return recordChange(extensionPoint, orphans, IExtensionDelta.ADDED);
	}

	private Set addExtensionsAndExtensionPoints(Contribution element) {
		// now add and resolve extensions and extension points
		Set affectedNamespaces = new HashSet();
		int[] extPoints = element.getExtensionPoints();
		for (int i = 0; i < extPoints.length; i++) {
			String namespace = this.addExtensionPoint(extPoints[i]);
			if (namespace != null)
				affectedNamespaces.add(namespace);
		}
		int[] extensions = element.getExtensions();
		for (int i = 0; i < extensions.length; i++) {
			String namespace = this.addExtension(extensions[i]);
			if (namespace != null)
				affectedNamespaces.add(namespace);
		}
		return affectedNamespaces;
	}

	public void addRegistryChangeListener(EventListener listener) {
		// this is just a convenience API - no need to do any sync'ing here		
		addRegistryChangeListener(listener, null);
	}

	public void addRegistryChangeListener(EventListener listener, String filter) {
		synchronized (listeners) {
			listeners.add(new ListenerInfo(listener, filter));
		}
	}

	private void basicAdd(Contribution element, boolean link) {
		// ignore anonymous namespaces
		if (element.getNamespace() == null)
			return;

		registryObjects.addContribution(element);
		if (!link)
			return;

		Set affectedNamespaces = addExtensionsAndExtensionPoints(element);
		setObjectManagers(affectedNamespaces, registryObjects.createDelegatingObjectManager(registryObjects.getAssociatedObjects(element.getContributorId())));
	}

	private void setObjectManagers(Set affectedNamespaces, IObjectManager manager) {
		for (Iterator iter = affectedNamespaces.iterator(); iter.hasNext();) {
			getDelta((String) iter.next()).setObjectManager(manager);
		}
	}

	private void basicRemove(long contributorId) {
		// ignore anonymous namespaces
		Set affectedNamespaces = removeExtensionsAndExtensionPoints(contributorId);
		Map associatedObjects = registryObjects.getAssociatedObjects(contributorId);
		registryObjects.removeObjects(associatedObjects);
		registryObjects.addNavigableObjects(associatedObjects); // put the complete set of navigable objects
		setObjectManagers(affectedNamespaces, registryObjects.createDelegatingObjectManager(associatedObjects));

		registryObjects.removeContribution(contributorId);
	}

	// allow other objects in the registry to use the same lock
	void enterRead() {
		access.enterRead();
	}

	// allow other objects in the registry to use the same lock	
	void exitRead() {
		access.exitRead();
	}

	/**
	 * Broadcasts (asynchronously) the event to all interested parties.
	 */
	private void fireRegistryChangeEvent() {
		// if there is nothing to say, just bail out
		if (deltas.isEmpty() || listeners.isEmpty())
			return;
		// for thread safety, create tmp collections
		Object[] tmpListeners = listeners.getListeners();
		Map tmpDeltas = new HashMap(this.deltas);
		// the deltas have been saved for notification - we can clear them now
		deltas.clear();
		// do the notification asynchronously
		strategy.scheduleChangeEvent(tmpListeners, tmpDeltas, this);
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getConfigurationElementsFor(java.lang.String)
	 */
	public IConfigurationElement[] getConfigurationElementsFor(String extensionPointId) {
		// this is just a convenience API - no need to do any sync'ing here		
		int lastdot = extensionPointId.lastIndexOf('.');
		if (lastdot == -1)
			return new IConfigurationElement[0];
		return getConfigurationElementsFor(extensionPointId.substring(0, lastdot), extensionPointId.substring(lastdot + 1));
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getConfigurationElementsFor(java.lang.String, java.lang.String)
	 */
	public IConfigurationElement[] getConfigurationElementsFor(String pluginId, String extensionPointSimpleId) {
		// this is just a convenience API - no need to do any sync'ing here
		IExtensionPoint extPoint = this.getExtensionPoint(pluginId, extensionPointSimpleId);
		if (extPoint == null)
			return new IConfigurationElement[0];
		return extPoint.getConfigurationElements();
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getConfigurationElementsFor(java.lang.String, java.lang.String, java.lang.String)
	 */
	public IConfigurationElement[] getConfigurationElementsFor(String pluginId, String extensionPointName, String extensionId) {
		// this is just a convenience API - no need to do any sync'ing here		
		IExtension extension = this.getExtension(pluginId, extensionPointName, extensionId);
		if (extension == null)
			return new IConfigurationElement[0];
		return extension.getConfigurationElements();
	}

	private RegistryDelta getDelta(String namespace) {
		// is there a delta for the plug-in?
		RegistryDelta existingDelta = (RegistryDelta) deltas.get(namespace);
		if (existingDelta != null)
			return existingDelta;

		//if not, create one
		RegistryDelta delta = new RegistryDelta();
		deltas.put(namespace, delta);
		return delta;
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getExtension(java.lang.String)
	 */
	public IExtension getExtension(String extensionId) {
		if (extensionId == null)
			return null;
		int lastdot = extensionId.lastIndexOf('.');
		if (lastdot == -1)
			return null;
		String namespace = extensionId.substring(0, lastdot);

		long[] contributorIds = getContributorIds(namespace);
		for (int i = 0; i < contributorIds.length; i++) {
			int[] extensions = registryObjects.getExtensionsFrom(contributorIds[i]);
			for (int j = 0; j < extensions.length; j++) {
				Extension ext = (Extension) registryObjects.getObject(extensions[j], RegistryObjectManager.EXTENSION);
				if (extensionId.equals(ext.getUniqueIdentifier()) && registryObjects.getExtensionPointObject(ext.getExtensionPointIdentifier()) != null) {
					return (IExtension) registryObjects.getHandle(extensions[j], RegistryObjectManager.EXTENSION);
				}
			}

		}
		return null;
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getExtension(java.lang.String, java.lang.String)
	 */
	public IExtension getExtension(String extensionPointId, String extensionId) {
		// this is just a convenience API - no need to do any sync'ing here		
		int lastdot = extensionPointId.lastIndexOf('.');
		if (lastdot == -1)
			return null;
		return getExtension(extensionPointId.substring(0, lastdot), extensionPointId.substring(lastdot + 1), extensionId);
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getExtension(java.lang.String, java.lang.String, java.lang.String)
	 */
	public IExtension getExtension(String pluginId, String extensionPointName, String extensionId) {
		// this is just a convenience API - no need to do any sync'ing here		
		IExtensionPoint extPoint = getExtensionPoint(pluginId, extensionPointName);
		if (extPoint != null)
			return extPoint.getExtension(extensionId);
		return null;
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getExtensionPoint(java.lang.String)
	 */
	public IExtensionPoint getExtensionPoint(String xptUniqueId) {
		return registryObjects.getExtensionPointHandle(xptUniqueId);
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getExtensionPoint(java.lang.String, java.lang.String)
	 */
	public IExtensionPoint getExtensionPoint(String elementName, String xpt) {
		access.enterRead();
		try {
			return registryObjects.getExtensionPointHandle(elementName + '.' + xpt);
		} finally {
			access.exitRead();
		}
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getExtensionPoints()
	 */
	public IExtensionPoint[] getExtensionPoints() {
		access.enterRead();
		try {
			return registryObjects.getExtensionPointsHandles();
		} finally {
			access.exitRead();
		}
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getExtensionPoints(java.lang.String)
	 */
	public IExtensionPoint[] getExtensionPoints(String namespace) {
		access.enterRead();
		try {
			long[] contributorIds = getContributorIds(namespace);
			IExtensionPoint[] result = ExtensionPointHandle.EMPTY_ARRAY;
			for (int i = 0; i < contributorIds.length; i++) {
				result = (IExtensionPoint[]) concatArrays(result, registryObjects.getHandles(registryObjects.getExtensionPointsFrom(contributorIds[i]), RegistryObjectManager.EXTENSION_POINT));
			}
			return result;
		} finally {
			access.exitRead();
		}
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getExtensions(java.lang.String)
	 */
	public IExtension[] getExtensions(String namespace) {
		access.enterRead();
		try {
			long[] contributorIds = getContributorIds(namespace);
			List tmp = new ArrayList();
			for (int i = 0; i < contributorIds.length; i++) {
				Extension[] exts = (Extension[]) registryObjects.getObjects(registryObjects.getExtensionsFrom(contributorIds[i]), RegistryObjectManager.EXTENSION);
				for (int j = 0; j < exts.length; j++) {
					if (registryObjects.getExtensionPointObject(exts[j].getExtensionPointIdentifier()) != null)
						tmp.add(registryObjects.getHandle(exts[j].getObjectId(), RegistryObjectManager.EXTENSION));
				}
			}
			if (tmp.size() == 0)
				return ExtensionHandle.EMPTY_ARRAY;
			IExtension[] result = new IExtension[tmp.size()];
			return (IExtension[]) tmp.toArray(result);
		} finally {
			access.exitRead();
		}
	}

	/*
	 *  (non-Javadoc)
	 * @see org.eclipse.core.runtime.IExtensionRegistry#getNamespaces()
	 */
	public String[] getNamespaces() {
		access.enterRead();
		try {
			Set namespaces = registryObjects.getNamespaces();
			String[] result = new String[namespaces.size()];
			return (String[]) namespaces.toArray(result);
		} finally {
			access.exitRead();
		}
	}

	public boolean hasNamespace(long name) {
		access.enterRead();
		try {
			return registryObjects.hasContribution(name);
		} finally {
			access.exitRead();
		}
	}

	private void link(ExtensionPoint extPoint, int[] extensions) {
		extPoint.setRawChildren(extensions);
		registryObjects.add(extPoint, true);
	}

	/*
	 * Records an extension addition/removal.
	 */
	private String recordChange(ExtensionPoint extPoint, int extension, int kind) {
		// avoid computing deltas when there are no listeners
		if (listeners.isEmpty())
			return null;
		ExtensionDelta extensionDelta = new ExtensionDelta();
		extensionDelta.setExtension(extension);
		extensionDelta.setExtensionPoint(extPoint.getObjectId());
		extensionDelta.setKind(kind);
		getDelta(extPoint.getNamespace()).addExtensionDelta(extensionDelta);
		return extPoint.getNamespace();
	}

	/*
	 * Records a set of extension additions/removals.
	 */
	private String recordChange(ExtensionPoint extPoint, int[] extensions, int kind) {
		if (listeners.isEmpty())
			return null;
		if (extensions == null || extensions.length == 0)
			return null;
		RegistryDelta pluginDelta = getDelta(extPoint.getNamespace());
		for (int i = 0; i < extensions.length; i++) {
			ExtensionDelta extensionDelta = new ExtensionDelta();
			extensionDelta.setExtension(extensions[i]);
			extensionDelta.setExtensionPoint(extPoint.getObjectId());
			extensionDelta.setKind(kind);
			pluginDelta.addExtensionDelta(extensionDelta);
		}
		return extPoint.getNamespace();
	}

	/**
	 * Unresolves and removes all extensions and extension points provided by
	 * the plug-in.
	 * <p>
	 * A corresponding IRegistryChangeEvent will be broadcast to all listeners
	 * interested on changes in the given plug-in.
	 * </p>
	 */
	public void remove(long removedContributorId) {
		access.enterWrite();
		try {
			basicRemove(removedContributorId);
			fireRegistryChangeEvent();
		} finally {
			access.exitWrite();
		}
	}

	//Return the affected namespace
	private String removeExtension(int extensionId) {
		Extension extension = (Extension) registryObjects.getObject(extensionId, RegistryObjectManager.EXTENSION);
		String xptName = extension.getExtensionPointIdentifier();
		ExtensionPoint extPoint = registryObjects.getExtensionPointObject(xptName);
		if (extPoint == null) {
			registryObjects.removeOrphan(xptName, extensionId);
			return null;
		}
		// otherwise, unlink the extension from the extension point
		int[] existingExtensions = extPoint.getRawChildren();
		int[] newExtensions = RegistryObjectManager.EMPTY_INT_ARRAY;
		if (existingExtensions.length > 1) {
			if (existingExtensions.length == 1)
				newExtensions = RegistryObjectManager.EMPTY_INT_ARRAY;

			newExtensions = new int[existingExtensions.length - 1];
			for (int i = 0, j = 0; i < existingExtensions.length; i++)
				if (existingExtensions[i] != extension.getObjectId())
					newExtensions[j++] = existingExtensions[i];
		}
		link(extPoint, newExtensions);
		return recordChange(extPoint, extension.getObjectId(), IExtensionDelta.REMOVED);
	}

	private String removeExtensionPoint(int extPoint) {
		ExtensionPoint extensionPoint = (ExtensionPoint) registryObjects.getObject(extPoint, RegistryObjectManager.EXTENSION_POINT);
		int[] existingExtensions = extensionPoint.getRawChildren();
		if (existingExtensions == null || existingExtensions.length == 0) {
			return null;
		}
		//Remove the extension point from the registry object
		registryObjects.addOrphans(extensionPoint.getUniqueIdentifier(), existingExtensions);
		link(extensionPoint, RegistryObjectManager.EMPTY_INT_ARRAY);
		return recordChange(extensionPoint, existingExtensions, IExtensionDelta.REMOVED);
	}

	private Set removeExtensionsAndExtensionPoints(long contributorId) {
		Set affectedNamespaces = new HashSet();
		int[] extensions = registryObjects.getExtensionsFrom(contributorId);
		for (int i = 0; i < extensions.length; i++) {
			String namespace = this.removeExtension(extensions[i]);
			if (namespace != null)
				affectedNamespaces.add(namespace);
		}

		// remove extension points
		int[] extPoints = registryObjects.getExtensionPointsFrom(contributorId);
		for (int i = 0; i < extPoints.length; i++) {
			String namespace = this.removeExtensionPoint(extPoints[i]);
			if (namespace != null)
				affectedNamespaces.add(namespace);
		}
		return affectedNamespaces;
	}

	public void removeRegistryChangeListener(EventListener listener) {
		synchronized (listeners) {
			listeners.remove(new ListenerInfo(listener, null));
		}
	}

	/**
	 * @param strategy - optional strategy that modify the behaviour of the extension registry.
	 * Might be null.
	 * @param key - the key token supplied by the owner of the registry. The same token should be
	 * passed to access-controlled methods of the registry. 
	 */
	public ExtensionRegistry(RegistryStrategy registryStrategy, Object key) {
		if (registryStrategy != null)
			strategy = registryStrategy;
		else
			strategy = new RegistryStrategy(null, true);
		// split strategies - reduce number of "instanceof" calls
		if (registryStrategy instanceof ICompatibilityStrategy)
			compatibilityStrategy = (ICompatibilityStrategy) strategy;
		// create the file manager right away
		setFileManager(strategy.getStorage(), strategy.isCacheReadOnly());

		token = key;
		registryObjects = new RegistryObjectManager(this);

		if (strategy.cacheUse()) {
			// Try to read the registry from the cache first. If that fails, create a new registry
			long start = 0;
			if (debug())
				start = System.currentTimeMillis();

			//The cache is made of several files, find the real names of these other files. If all files are found, try to initialize the objectManager
			if (checkCache()) {
				try {
					theTableReader.setTableFile(cacheFileManager.lookup(TableReader.TABLE, false));
					theTableReader.setExtraDataFile(cacheFileManager.lookup(TableReader.EXTRA, false));
					theTableReader.setMainDataFile(cacheFileManager.lookup(TableReader.MAIN, false));
					theTableReader.setContributionsFile(cacheFileManager.lookup(TableReader.CONTRIBUTIONS, false));
					theTableReader.setOrphansFile(cacheFileManager.lookup(TableReader.ORPHANS, false));
					isRegistryFilledFromCache = registryObjects.init(computeTimeStamp());
				} catch (IOException e) {
					// Ignore the exception. The registry will be rebuilt from the xml files.
				}
			}

			if (debug() && isRegistryFilledFromCache)
				System.out.println("Reading registry cache: " + (System.currentTimeMillis() - start)); //$NON-NLS-1$

			if (debug()) {
				if (!isRegistryFilledFromCache)
					System.out.println("Reloading registry from manifest files..."); //$NON-NLS-1$
				else
					System.out.println("Using registry cache..."); //$NON-NLS-1$
			}
		}

		if (debugEvents())
			addRegistryChangeListener(new IRegistryChangeListener() {
				public void registryChanged(IRegistryChangeEvent event) {
					System.out.println(event);
				}
			});

		// Do extra start processing if specified in the registry strategy
		strategy.onStart(this);
	}

	/**
	 * Stops the registry. Registry has to be stopped to properly
	 * close cache and dispose of listeners.
	 * @param key - key token for this registry
	 */
	public void stop(Object key) {
		// If the registry creator specified a key token, check that the key mathches it 
		// (it is assumed that registry owner keeps the key to prevent unautorized accesss).
		if (token != null && token != key) {
			throw new IllegalArgumentException("Unauthorized access to the ExtensionRegistry.stop() method. Check if proper access token is supplied."); //$NON-NLS-1$  
		}

		// Do extra stop processing if specified in the registry strategy
		strategy.onStop(this);

		stopChangeEventScheduler();

		if (cacheFileManager == null)
			return;

		if (!registryObjects.isDirty() || cacheFileManager.isReadOnly()) {
			cacheFileManager.close();
			return;
		}

		File tableFile = null;
		File mainFile = null;
		File extraFile = null;
		File contributionsFile = null;
		File orphansFile = null;

		TableWriter theTableWriter = new TableWriter(this);

		try {
			cacheFileManager.lookup(TableReader.TABLE, true);
			cacheFileManager.lookup(TableReader.MAIN, true);
			cacheFileManager.lookup(TableReader.EXTRA, true);
			cacheFileManager.lookup(TableReader.CONTRIBUTIONS, true);
			cacheFileManager.lookup(TableReader.ORPHANS, true);
			tableFile = File.createTempFile(TableReader.TABLE, ".new", cacheFileManager.getBase()); //$NON-NLS-1$
			mainFile = File.createTempFile(TableReader.MAIN, ".new", cacheFileManager.getBase()); //$NON-NLS-1$
			extraFile = File.createTempFile(TableReader.EXTRA, ".new", cacheFileManager.getBase()); //$NON-NLS-1$
			contributionsFile = File.createTempFile(TableReader.CONTRIBUTIONS, ".new", cacheFileManager.getBase()); //$NON-NLS-1$
			orphansFile = File.createTempFile(TableReader.ORPHANS, ".new", cacheFileManager.getBase()); //$NON-NLS-1$
			theTableWriter.setTableFile(tableFile);
			theTableWriter.setExtraDataFile(extraFile);
			theTableWriter.setMainDataFile(mainFile);
			theTableWriter.setContributionsFile(contributionsFile);
			theTableWriter.setOrphansFile(orphansFile);
		} catch (IOException e) {
			cacheFileManager.close();
			return; //Ignore the exception since we can recompute the cache
		}
		try {
			if (theTableWriter.saveCache(registryObjects, computeTimeStamp()))
				cacheFileManager.update(new String[] {TableReader.TABLE, TableReader.MAIN, TableReader.EXTRA, TableReader.CONTRIBUTIONS, TableReader.ORPHANS}, new String[] {tableFile.getName(), mainFile.getName(), extraFile.getName(), contributionsFile.getName(), orphansFile.getName()});
		} catch (IOException e) {
			//Ignore the exception since we can recompute the cache
		}

		cacheFileManager.close();
	}

	/*
	 * Clear the registry cache files from the file manager so on next start-up we recompute it.
	 */
	public void clearRegistryCache() {
		String[] keys = new String[] {TableReader.TABLE, TableReader.MAIN, TableReader.EXTRA, TableReader.CONTRIBUTIONS, TableReader.ORPHANS};
		for (int i = 0; i < keys.length; i++)
			try {
				cacheFileManager.remove(keys[i]);
			} catch (IOException e) {
				log(new Status(IStatus.ERROR, RegistryMessages.OWNER_NAME, IStatus.ERROR, RegistryMessages.meta_registryCacheReadProblems, e));
			}
	}

	/////////////////////////////////////////////////////////////////////////////////////////////////
	// Registry Object Factory
	// The factory produces contributions, extension points, extensions, and configuration elements 
	// to be stored in the extension registry.
	protected RegistryObjectFactory theRegistryObjectFactory = null;

	// Override to provide domain-specific elements to be stored in the extension registry
	protected void setElementFactory() {
		theRegistryObjectFactory = new RegistryObjectFactory(this);
	}

	// Lazy initialization.
	public RegistryObjectFactory getElementFactory() {
		if (theRegistryObjectFactory == null)
			setElementFactory();
		return theRegistryObjectFactory;
	}

	TableReader getCleanTableReader() {
		theTableReader.reset();
		return theTableReader;
	}

	public void log(IStatus status) {
		strategy.log(status);
	}

	public void setInitializationData(Object newClassInstance, IConfigurationElement confElement, String propertyName, Object initData) throws CoreException {
		if (compatibilityStrategy != null)
			compatibilityStrategy.setInitializationData(newClassInstance, confElement, propertyName, initData);
	}

	public String translate(String key, ResourceBundle resources) {
		return strategy.translate(key, resources);
	}

	public boolean debug() {
		return strategy.debug();
	}

	public boolean debugEvents() {
		return strategy.debugRegistryEvents();
	}

	public boolean useLazyCacheLoading() {
		return strategy.cacheLazyLoading();
	}

	public long computeState() {
		return strategy.cacheComputeState();
	}

	public long computeTimeStamp() {
		return strategy.cacheComputeTimeStamp();
	}

	// Check that cache is actually present in the specified location
	protected boolean checkCache() {
		File cacheFile = null;
		if (cacheFileManager != null) {
			try {
				cacheFile = cacheFileManager.lookup(TableReader.getTestFileName(), false);
			} catch (IOException e) {
				//Ignore the exception. The registry will be rebuilt from the xml files.
			}
		}
		if (cacheFile != null && cacheFile.isFile())
			return true; // primary location is fine

		// check alternative cache location if available
		File alternativeBase = strategy.cacheAlternativeLocation();
		if (alternativeBase != null) {
			setFileManager(alternativeBase, true);
			if (cacheFileManager != null) {
				// check this new location:
				cacheFile = null;
				try {
					cacheFile = cacheFileManager.lookup(TableReader.getTestFileName(), false);
				} catch (IOException e) {
					//Ignore the exception. The registry will be rebuilt from the xml files.
				}
				return (cacheFile != null && cacheFile.isFile());
			}
		}
		return false;
	}

	public boolean filledFromCache() {
		return isRegistryFilledFromCache;
	}

	public Object processExecutableExtension(String contributorName, long namespaceOwnerId, String namespaceName, String className, Object initData, String propertyName, ConfigurationElement confElement) throws CoreException {
		ConfigurationElementHandle confElementHandle = new ConfigurationElementHandle(getObjectManager(), confElement.getObjectId());
		return strategy.createExecutableExtension(contributorName, namespaceOwnerId, namespaceName, className, initData, propertyName, confElementHandle);
	}

	//////////////////////////////////////////////////////////////////////////////////////////
	// Registry namespace resolution

	public long getNamespaceOwnerId(long contributorId) {
		return strategy.getNamespaceOwnerId(contributorId);
	}

	public String getNamespace(long contributorId) {
		return strategy.getNamespace(contributorId);
	}

	public long[] getContributorIds(String namespace) {
		return strategy.getNamespaceContributors(namespace);
	}

	//////////////////////////////////////////////////////////////////////////////////////////
	// Registry change events processing

	public IStatus processChangeEvent(Object[] listenerInfos, Map deltas) {
		MultiStatus result = new MultiStatus(RegistryMessages.OWNER_NAME, IStatus.OK, RegistryMessages.plugin_eventListenerError, null);
		for (int i = 0; i < listenerInfos.length; i++) {
			ListenerInfo listenerInfo = (ListenerInfo) listenerInfos[i];
			if (listenerInfo.filter != null && !deltas.containsKey(listenerInfo.filter))
				continue;
			if (listenerInfo.listener instanceof IRegistryChangeListener)
				((IRegistryChangeListener) listenerInfo.listener).registryChanged(new RegistryChangeEvent(deltas, listenerInfo.filter));
			if (compatibilityStrategy != null)
				compatibilityStrategy.invokeListener(listenerInfo.listener, deltas, listenerInfo.filter);
		}
		for (Iterator iter = deltas.values().iterator(); iter.hasNext();) {
			((RegistryDelta) iter.next()).getObjectManager().close();
		}
		return result;
	}

	private RegistryEventThread eventThread = null; // registry event loop
	private final List queue = new LinkedList(); // stores registry events info

	// Registry events notifications are done on a separate thread in a sequential manner
	// (first in - first processed)
	public void scheduleChangeEvent(Object[] listenerInfos, Map deltas) {
		QueueElement newElement = new QueueElement(listenerInfos, deltas);
		if (eventThread == null) {
			eventThread = new RegistryEventThread(this);
			eventThread.start();
		}
		synchronized (queue) {
			queue.add(newElement);
			queue.notify();
		}
	}

	// The pair of values we store in the event queue
	private class QueueElement {
		Object[] listenerInfos;
		Map deltas;

		QueueElement(Object[] infos, Map deltas) {
			this.deltas = deltas;
			listenerInfos = infos;
		}
	}

	private class RegistryEventThread extends Thread {
		private ExtensionRegistry registry;

		public RegistryEventThread(ExtensionRegistry registry) {
			super("Extension Registry Event Dispatcher"); //$NON-NLS-1$
			setDaemon(true);
			this.registry = registry;
		}

		public void run() {
			while (true) {
				QueueElement element;
				synchronized (queue) {
					try {
						while (queue.isEmpty())
							queue.wait();
					} catch (InterruptedException e) {
						return;
					}
					element = (QueueElement) queue.remove(0);
				}
				registry.processChangeEvent(element.listenerInfos, element.deltas);
			}
		}
	}

	protected void stopChangeEventScheduler() {
		if (eventThread != null) {
			synchronized (queue) {
				eventThread.interrupt();
				eventThread = null;
			}
		}
	}

	//////////////////////////////////////////////////////////////////////////////////////
	// Modifiable portion

	/* (non-Javadoc)
	 * @see org.eclipse.equinox.registry.ISPIExtensionRegistry#addXMLContribution(java.io.InputStream, long, javax.xml.parsers.SAXParserFactory)
	 */
	public boolean addXMLContribution(InputStream is, long contributorId, String contributionType, String contributionName, ResourceBundle b, Object key) {
		// If the registry is not modifiable, check that the proper key was passed in 
		if (!strategy.isModifiable() && token != key) {
			throw new IllegalArgumentException("Unauthorized access to the ExtensionRegistry.addXMLContribution() method. Check if proper access token is supplied."); //$NON-NLS-1$  
		}

		String ownerName = getNamespace(contributorId);
		String message = NLS.bind(RegistryMessages.parse_problems, ownerName);
		MultiStatus problems = new MultiStatus(RegistryMessages.OWNER_NAME, ExtensionsParser.PARSE_PROBLEM, message, null);
		ExtensionsParser parser = new ExtensionsParser(problems, this);
		Contribution contribution = getElementFactory().createContribution(contributorId);

		try {
			parser.parseManifest(strategy.getXMLParser(), new InputSource(is), contributionType, contributionName, getObjectManager(), contribution, b);
			if (problems.getSeverity() != IStatus.OK) {
				log(problems);
				return false;
			}
		} catch (ParserConfigurationException e) {
			logError(ownerName, contributionName, e);
			return false;
		} catch (SAXException e) {
			logError(ownerName, contributionName, e);
			return false;
		} catch (IOException e) {
			logError(ownerName, contributionName, e);
			return false;
		} finally {
			try {
				is.close();
			} catch (IOException ioe) {
				// nothing to do
			}
		}

		// Do not synchronize on registry here because the registry handles
		// the synchronization for us in registry.add		
		add(contribution);
		return true;
	}

	private void logError(String owner, String contributionName, Exception e) {
		String message = NLS.bind(RegistryMessages.parse_failedParsingManifest, owner + "/" + contributionName); //$NON-NLS-1$
		log(new Status(IStatus.ERROR, RegistryMessages.OWNER_NAME, 0, message, e));
	}

	/**
	 * Creates an extension point.
	 * 
	 * @param contributorId - Id of the supplier of this extension point
	 * @param extensionPointId - Id of the extension point. If non-qualified names is supplied,
	 * it will be converted internally into a fully qualified name.
	 * @param extensionPointLabel- display string for the extension point
	 * @param schemaLocation - points to the location of the XML schema file
	 */
	public void createExtensionPoint(long contributorId, String extensionPointId, String extensionPointLabel, String schemaLocation) {

		// Extension point Id might not be null
		if (extensionPointId == null) {
			String message = NLS.bind(RegistryMessages.create_failedExtensionPoint, extensionPointLabel);
			log(new Status(IStatus.ERROR, RegistryMessages.OWNER_NAME, 0, message, null));
		}

		// prepare namespace information
		String namespaceName = getNamespace(contributorId);
		long namespaceOwnerId = getNamespaceOwnerId(contributorId);

		// addition wraps in a contribution
		Contribution contribution = getElementFactory().createContribution(contributorId);

		ExtensionPoint currentExtPoint = getElementFactory().createExtensionPoint();
		String uniqueId = namespaceName + '.' + extensionPointId;
		currentExtPoint.setUniqueIdentifier(uniqueId);
		String labelNLS = translate(extensionPointLabel, null);
		currentExtPoint.setLabel(labelNLS);
		currentExtPoint.setSchema(schemaLocation);

		getObjectManager().addExtensionPoint(currentExtPoint, true);

		currentExtPoint.setNamespace(namespaceName);
		currentExtPoint.setNamespaceOwnerId(namespaceOwnerId);

		// array format: {Number of extension points, Number of extensions, Extension Id}
		int[] contributionChildren = new int[3];
		// Put the extension points into this namespace
		contributionChildren[Contribution.EXTENSION_POINT] = 1;
		contributionChildren[Contribution.EXTENSION] = 0;
		contributionChildren[Contribution.EXTENSION + 1] = currentExtPoint.getObjectId();

		contribution.setRawChildren(contributionChildren);

		add(contribution);
	}

	/**
	 * Creates an extension.
	 * 
	 * @see ExtensionDescription
	 * 
	 * @param contributorId - Id of the supplier of this extension
	 * @param extensionId - Id of the extension. If non-qualified name is supplied,
	 * it will be converted internally into a fully qualified name
	 * @param extensionLabel - display string for this extension
	 * @param extensionPointId - Id of the point being extended. If non-qualified
	 * name is supplied, it is assumed to have the same contributorId as this extension
	 * @param description - contents of the extension
	 */
	public void createExtension(long contributorId, String extensionId, String extensionLabel, String extensionPointId, ExtensionDescription description) {
		// prepare namespace information
		String namespaceName = getNamespace(contributorId);
		long namespaceOwnerId = getNamespaceOwnerId(contributorId);

		// addition wraps in a contribution
		Contribution contribution = getElementFactory().createContribution(contributorId);

		Extension currentExtension = getElementFactory().createExtension();
		currentExtension.setSimpleIdentifier(extensionId);
		String extensionLabelNLS = translate(extensionLabel, null);
		currentExtension.setLabel(extensionLabelNLS);

		String targetExtensionPointId;
		if (extensionPointId.indexOf('.') == -1) // No dots -> namespace name added at the start
			targetExtensionPointId = namespaceName + '.' + extensionPointId;
		else
			targetExtensionPointId = extensionPointId;
		currentExtension.setExtensionPointIdentifier(targetExtensionPointId);

		getObjectManager().add(currentExtension, true);

		createExtensionData(namespaceOwnerId, description, currentExtension);

		currentExtension.setNamespace(namespaceName);

		int[] contributionChildren = new int[3];

		contributionChildren[Contribution.EXTENSION_POINT] = 0;
		contributionChildren[Contribution.EXTENSION] = 1;
		contributionChildren[Contribution.EXTENSION + 1] = currentExtension.getObjectId();
		contribution.setRawChildren(contributionChildren);

		add(contribution);
	}

	// Fill in the actual content of this extension
	private void createExtensionData(long namespaceOwnerId, ExtensionDescription description, RegistryObject parent) {
		ConfigurationElement currentConfigurationElement = getElementFactory().createConfigurationElement();
		currentConfigurationElement.setNamespaceOwnerId(namespaceOwnerId);
		currentConfigurationElement.setName(description.getElementName());

		if (description.hasProperties()) {
			ExtensionProperty[] descriptionProperties = description.getProperties();
			int len = descriptionProperties.length;

			String[] properties = new String[len * 2];
			for (int i = 0; i < len; i++) {
				properties[i * 2] = descriptionProperties[i].getName();
				properties[i * 2 + 1] = translate(descriptionProperties[i].getValue(), null);
			}
			currentConfigurationElement.setProperties(properties);
		} else
			currentConfigurationElement.setProperties(RegistryObjectManager.EMPTY_STRING_ARRAY);

		String value = description.getValue();
		if (value != null)
			currentConfigurationElement.setValue(value);

		getObjectManager().add(currentConfigurationElement, true);

		// process children
		ExtensionDescription[] children = description.getChildren();
		if (children != null) {
			for (int i = 0; i < children.length; i++) {
				createExtensionData(namespaceOwnerId, children[i], currentConfigurationElement);
			}
		}

		int[] oldValues = parent.getRawChildren();
		int size = oldValues.length;
		int[] newValues = new int[size + 1];
		for (int i = 0; i < size; i++) {
			newValues[i] = oldValues[i];
		}
		newValues[size] = currentConfigurationElement.getObjectId();
		parent.setRawChildren(newValues);
		currentConfigurationElement.setParentId(parent.getObjectId());
		currentConfigurationElement.setParentType(parent instanceof ConfigurationElement ? RegistryObjectManager.CONFIGURATION_ELEMENT : RegistryObjectManager.EXTENSION);
	}

	public void setCompatibilityStrategy(ICompatibilityStrategy strategy) {
		compatibilityStrategy = strategy;
	}

}
