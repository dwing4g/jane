/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.mina.core.write;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * An exception which is thrown when one or more write operations were attempted on a closed session.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public final class WriteToClosedSessionException extends IOException {
	/** The mandatory serialVersionUUID */
	private static final long serialVersionUID = 5550204573739301393L;

	/** The list of WriteRequest stored in this exception */
	private final List<WriteRequest> requests;

	/**
	 * Create a new WriteToClosedSessionException instance
	 *
	 * @param request The {@link WriteRequest} which has been written on a closed session
	 */
	public WriteToClosedSessionException(WriteRequest request) {
		super();
		requests = asRequestList(request);
	}

	/**
	 * Create a new WriteToClosedSessionException instance
	 *
	 * @param requests The {@link WriteRequest}s which have been written on a closed session
	 */
	public WriteToClosedSessionException(Collection<WriteRequest> requests) {
		super();
		this.requests = asRequestList(requests);
	}

	/**
	 * Create a new WriteToClosedSessionException instance
	 *
	 * @param requests The {@link WriteRequest}s which have been written on a closed session
	 * @param message The error message
	 * @param cause The original exception
	 */
	public WriteToClosedSessionException(Collection<WriteRequest> requests, String message, Throwable cause) {
		super(message);
		initCause(cause);
		this.requests = asRequestList(requests);
	}

	/**
	 * @return the list of the failed {@link WriteRequest}, in the order of occurrence.
	 */
	public List<WriteRequest> getRequests() {
		return requests;
	}

	private static List<WriteRequest> asRequestList(Collection<WriteRequest> requests) {
		if (requests == null) {
			throw new IllegalArgumentException("requests");
		}

		if (requests.isEmpty()) {
			throw new IllegalArgumentException("requests is empty.");
		}

		// Create a list of requests removing duplicates.
		LinkedHashSet<WriteRequest> newRequests = new LinkedHashSet<>();

		for (WriteRequest r : requests) {
			newRequests.add(r.getOriginalRequest());
		}

		return Collections.unmodifiableList(new ArrayList<>(newRequests));
	}

	private static List<WriteRequest> asRequestList(WriteRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("request");
		}

		List<WriteRequest> requests = new ArrayList<>(1);
		requests.add(request.getOriginalRequest());

		return Collections.unmodifiableList(requests);
	}
}
