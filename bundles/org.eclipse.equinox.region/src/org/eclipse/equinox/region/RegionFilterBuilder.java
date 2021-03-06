/*******************************************************************************
 * Copyright (c) 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.equinox.region;

import org.osgi.framework.InvalidSyntaxException;

/**
 * A builder for creating {@link RegionFilter} instances. A builder instance can be obtained from the
 * {@link RegionDigraph#createRegionFilterBuilder()} method.
 * <p />
 * <strong>Concurrent Semantics</strong><br />
 * 
 * Implementations of this interface must be thread safe.
 */
public interface RegionFilterBuilder {

	/**
	 * Allow capabilities with the given name space matching the given filter.
	 * 
	 * @param namespace the name space of the capabilities to be allowed
	 * @param filter the filter matching the capabilities to be allowed
	 * @return this builder (for method chaining)
	 */
	RegionFilterBuilder allow(String namespace, String filter) throws InvalidSyntaxException;

	/**
	 * Allow all capabilities with the given name space.
	 * 
	 * @param namespace the name space of the capabilities to be allowed
	 * @return this builder (for method chaining)
	 */
	RegionFilterBuilder allowAll(String namespace);

	/**
	 * Build a {@link RegionFilter} from the current state of this builder.
	 * 
	 * @return the {@link RegionFilter} built
	 */
	RegionFilter build();
}
