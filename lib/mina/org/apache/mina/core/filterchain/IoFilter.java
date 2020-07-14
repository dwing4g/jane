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
package org.apache.mina.core.filterchain;

import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;

/**
 * A filter which intercepts {@link IoHandler} events like Servlet
 * filters. Filters can be used for these purposes:
 * <ul>
 *   <li>Event logging,</li>
 *   <li>Performance measurement,</li>
 *   <li>Authorization,</li>
 *   <li>Overload control,</li>
 *   <li>Message transformation (e.g. encryption and decryption, ...),</li>
 *   <li>and many more.</li>
 * </ul>
 * <p>
 * <strong>Please NEVER implement your filters to wrap
 * {@link IoSession}s.</strong> Users can cache the reference to the
 * session, which might malfunction if any filters are added or removed later.
 *
 * <h3>The Life Cycle</h3>
 * {@link IoFilter}s are activated only when they are inside {@link IoFilterChain}.
 * <p>
 * When you add an {@link IoFilter} to an {@link IoFilterChain}:
 * <ol>
 *   <li>{@link #onPreAdd(IoFilterChain, String, NextFilter)} is invoked to notify
 *       that the filter will be added to the chain.</li>
 *   <li>The filter is added to the chain, and all events and I/O requests
 *       pass through the filter from now.</li>
 *   <li>{@link #onPostAdd(IoFilterChain, String, NextFilter)} is invoked to notify
 *       that the filter is added to the chain.</li>
 *   <li>The filter is removed from the chain if {@link #onPostAdd(IoFilterChain, String, org.apache.mina.core.filterchain.IoFilter.NextFilter)}
 *       threw an exception.
 * </ol>
 * <p>
 * When you remove an {@link IoFilter} from an {@link IoFilterChain}:
 * <ol>
 *   <li>{@link #onPreRemove(IoFilterChain, String, NextFilter)} is invoked to
 *       notify that the filter will be removed from the chain.</li>
 *   <li>The filter is removed from the chain, and any events and I/O requests
 *       don't pass through the filter from now.</li>
 *   <li>{@link #onPostRemove(IoFilterChain, String, NextFilter)} is invoked to
 *       notify that the filter is removed from the chain.</li>
 * </ol>
 *
 * You can implement this interface and selectively override required event filter methods only.
 * All methods forwards events to the next filter by default.
 */
public interface IoFilter {
	/**
	 * Invoked before this filter is added to the specified <tt>parent</tt>.
	 * Please note that this method can be invoked more than once if
	 * this filter is added to more than one parents.
	 *
	 * @param parent the parent who called this method
	 * @param name the name assigned to this filter
	 * @param nextFilter the {@link NextFilter} for this filter.
	 *            You can reuse this object until this filter is removed from the chain.
	 * @throws Exception If an error occurred while processing the event
	 */
	default void onPreAdd(IoFilterChain parent, String name, NextFilter nextFilter) throws Exception {
	}

	/**
	 * Invoked after this filter is added to the specified <tt>parent</tt>.
	 * Please note that this method can be invoked more than once if
	 * this filter is added to more than one parents.
	 *
	 * @param parent the parent who called this method
	 * @param name the name assigned to this filter
	 * @param nextFilter the {@link NextFilter} for this filter.
	 *            You can reuse this object until this filter is removed from the chain.
	 * @throws Exception If an error occurred while processing the event
	 */
	default void onPostAdd(IoFilterChain parent, String name, NextFilter nextFilter) throws Exception {
	}

	/**
	 * Invoked before this filter is removed from the specified <tt>parent</tt>.
	 * Please note that this method can be invoked more than once if
	 * this filter is removed from more than one parents.
	 *
	 * @param parent the parent who called this method
	 * @param name the name assigned to this filter
	 * @param nextFilter the {@link NextFilter} for this filter.
	 *            You can reuse this object until this filter is removed from the chain.
	 * @throws Exception If an error occurred while processing the event
	 */
	default void onPreRemove(IoFilterChain parent, String name, NextFilter nextFilter) throws Exception {
	}

	/**
	 * Invoked after this filter is removed from the specified <tt>parent</tt>.
	 * Please note that this method can be invoked more than once if
	 * this filter is removed from more than one parents.
	 *
	 * @param parent the parent who called this method
	 * @param name the name assigned to this filter
	 * @param nextFilter the {@link NextFilter} for this filter.
	 *            You can reuse this object until this filter is removed from the chain.
	 * @throws Exception If an error occurred while processing the event
	 */
	@SuppressWarnings("RedundantThrows")
	default void onPostRemove(IoFilterChain parent, String name, NextFilter nextFilter) throws Exception {
	}

	/**
	 * Filters {@link IoHandler#sessionCreated(IoSession)} event.
	 *
	 * @param nextFilter the {@link NextFilter} for this filter.
	 *            You can reuse this object until this filter is removed from the chain.
	 * @param session The {@link IoSession} which has received this event
	 * @throws Exception If an error occurred while processing the event
	 */
	default void sessionCreated(NextFilter nextFilter, IoSession session) throws Exception {
		nextFilter.sessionCreated();
	}

	/**
	 * Filters {@link IoHandler#sessionOpened(IoSession)} event.
	 *
	 * @param nextFilter the {@link NextFilter} for this filter.
	 *            You can reuse this object until this filter is removed from the chain.
	 * @param session The {@link IoSession} which has received this event
	 * @throws Exception If an error occurred while processing the event
	 */
	default void sessionOpened(NextFilter nextFilter, IoSession session) throws Exception {
		nextFilter.sessionOpened();
	}

	/**
	 * Filters {@link IoHandler#messageReceived(IoSession,Object)} event.
	 *
	 * @param nextFilter the {@link NextFilter} for this filter.
	 *            You can reuse this object until this filter is removed from the chain.
	 * @param session The {@link IoSession} which has received this event
	 * @param message The received message
	 * @throws Exception If an error occurred while processing the event
	 */
	default void messageReceived(NextFilter nextFilter, IoSession session, Object message) throws Exception {
		nextFilter.messageReceived(message);
	}

	/**
	 * Filters {@link IoSession#write(Object)} method invocation.
	 *
	 * @param nextFilter the {@link NextFilter} for this filter.
	 *            You can reuse this object until this filter is removed from the chain.
	 * @param session The {@link IoSession} which has to process this invocation
	 * @param writeRequest The {@link WriteRequest} to process
	 * @throws Exception If an error occurred while processing the event
	 */
	default void filterWrite(NextFilter nextFilter, IoSession session, WriteRequest writeRequest) throws Exception {
		nextFilter.filterWrite(writeRequest);
	}

	/**
	 * Filters {@link IoSession#closeNow()} or a {@link IoSession#closeOnFlush()} method invocations.
	 *
	 * @param nextFilter the {@link NextFilter} for this filter.
	 *            You can reuse this object until this filter is removed from the chain.
	 * @param session The {@link IoSession} which has to process this method invocation
	 * @throws Exception If an error occurred while processing the event
	 */
	default void filterClose(NextFilter nextFilter, IoSession session) throws Exception {
		nextFilter.filterClose();
	}

	/**
	 * Filters {@link IoHandler#inputClosed(IoSession)} event.
	 *
	 * @param nextFilter the {@link NextFilter} for this filter.
	 *            You can reuse this object until this filter is removed from the chain.
	 * @param session The {@link IoSession} which has received this event
	 * @throws Exception If an error occurred while processing the event
	 */
	default void inputClosed(NextFilter nextFilter, IoSession session) throws Exception {
		nextFilter.inputClosed();
	}

	/**
	 * Filters {@link IoHandler#sessionClosed(IoSession)} event.
	 *
	 * @param nextFilter the {@link NextFilter} for this filter.
	 *            You can reuse this object until this filter is removed from the chain.
	 * @param session The {@link IoSession} which has received this event
	 * @throws Exception If an error occurred while processing the event
	 */
	default void sessionClosed(NextFilter nextFilter, IoSession session) throws Exception {
		nextFilter.sessionClosed();
	}

	/**
	 * Filters {@link IoHandler#exceptionCaught(IoSession,Throwable)} event.
	 *
	 * @param nextFilter the {@link NextFilter} for this filter.
	 *            You can reuse this object until this filter is removed from the chain.
	 * @param session The {@link IoSession} which has received this event
	 * @param cause The exception that cause this event to be received
	 * @throws Exception If an error occurred while processing the event
	 */
	default void exceptionCaught(NextFilter nextFilter, IoSession session, Throwable cause) throws Exception {
		nextFilter.exceptionCaught(cause);
	}

	/**
	 * Represents the next {@link IoFilter} in {@link IoFilterChain}.
	 */
	interface NextFilter {
		/**
		 * Forwards <tt>sessionCreated</tt> event to next filter.
		 */
		void sessionCreated() throws Exception;

		/**
		 * Forwards <tt>sessionOpened</tt> event to next filter.
		 */
		void sessionOpened() throws Exception;

		/**
		 * Forwards <tt>messageReceived</tt> event to next filter.
		 *
		 * @param message The received message
		 */
		void messageReceived(Object message) throws Exception;

		/**
		 * Forwards <tt>filterWrite</tt> event to next filter.
		 *
		 * @param writeRequest The {@link WriteRequest} to process
		 */
		void filterWrite(WriteRequest writeRequest) throws Exception;

		/**
		 * Forwards <tt>filterClose</tt> event to next filter.
		 */
		void filterClose() throws Exception;

		/**
		 * Forwards <tt>inputClosed</tt> event to next filter.
		 */
		void inputClosed() throws Exception;

		/**
		 * Forwards <tt>sessionClosed</tt> event to next filter.
		 */
		void sessionClosed() throws Exception;

		/**
		 * Forwards <tt>exceptionCaught</tt> event to next filter.
		 *
		 * @param cause The exception that cause this event to be received
		 */
		void exceptionCaught(Throwable cause) throws Exception;
	}
}
