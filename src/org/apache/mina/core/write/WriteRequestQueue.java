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

import org.apache.mina.core.session.IoSession;

/** Stores {@link WriteRequest}s which are queued to an {@link IoSession}. */
public interface WriteRequestQueue {
	/**
	 * Add a new WriteRequest to the session write's queue at last
	 *
	 * @param writeRequest The writeRequest to add
	 * @return {@code true} if the writeRequest was added to this queue, else {@code false}
	 */
	boolean offer(WriteRequest writeRequest);

	/**
	 * Peek the first request available in the queue for a session.
	 *
	 * @return The first available request, if any.
	 */
	WriteRequest peek();

	/**
	 * Poll the first request available in the queue for a session.
	 *
	 * @return The first available request, if any.
	 */
	WriteRequest poll();
}
