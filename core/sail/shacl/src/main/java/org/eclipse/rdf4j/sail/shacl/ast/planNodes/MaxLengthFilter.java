/*******************************************************************************
 * Copyright (c) 2020 Eclipse RDF4J contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 *******************************************************************************/

package org.eclipse.rdf4j.sail.shacl.ast.planNodes;

import java.util.Objects;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.sail.shacl.wrapper.data.ConnectionsGroup;

/**
 * @author Håvard Ottestad
 */
public class MaxLengthFilter extends FilterPlanNode {

	private final long maxLength;

	public MaxLengthFilter(PlanNode parent, long maxLength, ConnectionsGroup connectionsGroup) {
		super(parent, connectionsGroup);
		this.maxLength = maxLength;
	}

	@Override
	boolean checkTuple(Reference t) {
		Value literal = t.get().getValue();

		return literal.stringValue().length() <= maxLength;
	}

	@Override
	public String toString() {
		return "MaxLengthFilter{" + "maxLength=" + maxLength + '}';
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		if (!super.equals(o)) {
			return false;
		}
		MaxLengthFilter that = (MaxLengthFilter) o;
		return maxLength == that.maxLength;
	}

	@Override
	public int hashCode() {
		return Objects.hash(super.hashCode(), maxLength);
	}
}
