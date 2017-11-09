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
package org.apache.mina.core.session;

import java.net.InetSocketAddress;
import java.util.Set;
import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.filterchain.IoFilterChain;
import org.apache.mina.core.future.CloseFuture;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.IoService;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.core.write.WriteRequestQueue;
import org.apache.mina.transport.socket.AbstractSocketSessionConfig;

/**
 * <p>
 *   A handle which represents connection between two end-points regardless of transport types.
 * </p>
 * <p>
 *   {@link IoSession} provides user-defined attributes.
 *   User-defined attributes are application-specific data which are associated with a session.
 *   It often contains objects that represents the state of a higher-level protocol
 *   and becomes a way to exchange data between filters and handlers.
 * </p>
 * <h3>Adjusting Transport Type Specific Properties</h3>
 * <p>
 *   You can simply downcast the session to an appropriate subclass.
 * </p>
 * <h3>Thread Safety</h3>
 * <p>
 *   {@link IoSession} is thread-safe.  But please note that performing
 *   more than one {@link #write(Object)} calls at the same time will
 *   cause the {@link IoFilter#filterWrite(IoFilter.NextFilter,IoSession,WriteRequest)}
 *   to be executed simultaneously, and therefore you have to make sure the
 *   {@link IoFilter} implementations you're using are thread-safe, too.
 * </p>
 * <h3>Equality of Sessions</h3>
 * TODO: The getId() method is totally wrong.
 * We can't base a method which is designed to create a unique ID on the hashCode method.
 * {@link Object#equals(Object)} and {@link Object#hashCode()} shall not be overriden
 * to the default behavior that is defined in {@link Object}.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public interface IoSession {
	/**
	 * @return a unique identifier for this session.
	 *         Every session has its own ID which is different from each other.
	 */
	long getId();

	/**
	 * @return the {@link IoService} which provides I/O service to this session.
	 */
	IoService getService();

	/**
	 * @return the {@link IoHandler} which handles this session.
	 */
	IoHandler getHandler();

	/**
	 * @return the configuration of this session.
	 */
	AbstractSocketSessionConfig getConfig();

	/**
	 * @return the filter chain that only affects this session.
	 */
	IoFilterChain getFilterChain();

	/**
	 * Get the queue that contains the message waiting for being written.
	 * As the reader might not be ready, it's frequent that the messages aren't written completely,
	 * or that some older messages are waiting to be written when a new message arrives.
	 * This queue is used to manage the backlog of messages.
	 *
	 * @return The queue containing the pending messages.
	 */
	WriteRequestQueue getWriteRequestQueue();

	/**
	 * Writes the specified <code>message</code> to remote peer. This operation is asynchronous.
	 * You can wait for the returned {@link WriteFuture} if you want to wait for the message actually written.
	 *
	 * @param message The message to write
	 * @return The associated WriteFuture
	 */
	WriteFuture write(Object message);

	/**
	 * Closes this session immediately.  This operation is asynchronous, it returns a {@link CloseFuture}.
	 *
	 * @return The {@link CloseFuture} that can be use to wait for the completion of this operation
	 */
	CloseFuture closeNow();

	/**
	 * Closes this session after all queued write requests are flushed. This operation is asynchronous.
	 * Wait for the returned {@link CloseFuture} if you want to wait for the session actually closed.
	 *
	 * @return The associated CloseFuture
	 */
	CloseFuture closeOnFlush();

	/**
	 * Returns an attachment of this session.
	 *
	 * @return The attachment
	 */
	Object getAttachment();

	/**
	 * Sets an attachment of this session.
	 *
	 * @param attachment The attachment
	 * @return Old attachment. <tt>null</tt> if it is new.
	 */
	Object setAttachment(Object attachment);

	/**
	 * Returns the value of the user-defined attribute of this session.
	 *
	 * @param key the key of the attribute
	 * @return <tt>null</tt> if there is no attribute with the specified key
	 */
	Object getAttribute(Object key);

	/**
	 * Returns the value of user defined attribute associated with the specified key.
	 * If there's no such attribute, the specified default value is associated with the specified key,
	 * and the default value is returned.
	 * This method is same with the following code except that the operation is performed atomically.
	 * <pre>
	 * if (containsAttribute(key)) {
	 *     return getAttribute(key);
	 * } else {
	 *     setAttribute(key, defaultValue);
	 *     return defaultValue;
	 * }
	 * </pre>
	 *
	 * @param key the key of the attribute we want to retreive
	 * @param defaultValue the default value of the attribute
	 * @return The retrieved attribute or <tt>null</tt> if not found
	 */
	Object getAttribute(Object key, Object defaultValue);

	/**
	 * Sets a user-defined attribute.
	 *
	 * @param key the key of the attribute
	 * @param value the value of the attribute
	 * @return The old value of the attribute.  <tt>null</tt> if it is new.
	 */
	Object setAttribute(Object key, Object value);

	/**
	 * Sets a user defined attribute if the attribute with the specified key is not set yet.
	 * This method is same with the following code except that the operation is performed atomically.
	 * <pre>
	 * if (containsAttribute(key)) {
	 *     return getAttribute(key);
	 * } else {
	 *     return setAttribute(key, value);
	 * }
	 * </pre>
	 *
	 * @param key The key of the attribute we want to set
	 * @param value The value we want to set
	 * @return The old value of the attribute.  <tt>null</tt> if not found.
	 */
	Object setAttributeIfAbsent(Object key, Object value);

	/**
	 * Removes a user-defined attribute with the specified key.
	 *
	 * @param key The key of the attribute we want to remove
	 * @return The old value of the attribute.  <tt>null</tt> if not found.
	 */
	Object removeAttribute(Object key);

	/**
	 * Removes a user defined attribute with the specified key
	 * if the current attribute value is equal to the specified value.
	 * This method is same with the following code except that the operation is performed atomically.
	 * <pre>
	 * if (containsAttribute(key) &amp;&amp; getAttribute(key).equals(value)) {
	 *     removeAttribute(key);
	 *     return true;
	 * } else {
	 *     return false;
	 * }
	 * </pre>
	 *
	 * @param key The key we want to remove
	 * @param value The value we want to remove
	 * @return <tt>true</tt> if the removal was successful
	 */
	boolean removeAttribute(Object key, Object value);

	/**
	 * Replaces a user defined attribute with the specified key
	 * if the value of the attribute is equals to the specified old value.
	 * This method is same with the following code except that the operation is performed atomically.
	 * <pre>
	 * if (containsAttribute(key) &amp;&amp; getAttribute(key).equals(oldValue)) {
	 *     setAttribute(key, newValue);
	 *     return true;
	 * } else {
	 *     return false;
	 * }
	 * </pre>
	 *
	 * @param key The key we want to replace
	 * @param oldValue The previous value
	 * @param newValue The new value
	 * @return <tt>true</tt> if the replacement was successful
	 */
	boolean replaceAttribute(Object key, Object oldValue, Object newValue);

	/**
	 * @param key The key of the attribute we are looking for in the session
	 * @return <tt>true</tt> if this session contains the attribute with the specified <tt>key</tt>.
	 */
	boolean containsAttribute(Object key);

	/**
	 * @return the set of keys of all user-defined attributes.
	 */
	Set<Object> getAttributeKeys();

	/**
	 * @return <tt>true</tt> if this session is connected with remote peer.
	 */
	boolean isConnected();

	/**
	 * @return <tt>true</tt> if this session is active.
	 */
	boolean isActive();

	/**
	 * @return <tt>true</tt> if and only if this session is being closed
	 * (but not disconnected yet) or is closed.
	 */
	boolean isClosing();

	/**
	 * @return the {@link CloseFuture} of this session.
	 * This method returns the same instance whenever user calls it.
	 */
	CloseFuture getCloseFuture();

	/**
	 * @return the socket address of local machine which is associated with this session.
	 */
	InetSocketAddress getLocalAddress();

	/**
	 * @return the socket address of remote peer.
	 */
	InetSocketAddress getRemoteAddress();

	/**
	 * Returns the {@link WriteRequest} which is being processed by {@link IoService}.
	 *
	 * @return <tt>null</tt> if and if only no message is being written
	 */
	WriteRequest getCurrentWriteRequest();

	/**
	 * Associate the current write request with the session
	 *
	 * @param currentWriteRequest the current write request to associate
	 */
	void setCurrentWriteRequest(WriteRequest currentWriteRequest);

	/**
	 * Suspends read operations for this session.
	 */
	void suspendRead();

	/**
	 * Suspends write operations for this session.
	 */
	void suspendWrite();

	/**
	 * Resumes read operations for this session.
	 */
	void resumeRead();

	/**
	 * Resumes write operations for this session.
	 */
	void resumeWrite();

	/**
	 * Is read operation is suspended for this session.
	 *
	 * @return <tt>true</tt> if suspended
	 */
	boolean isReadSuspended();

	/**
	 * Is write operation is suspended for this session.
	 *
	 * @return <tt>true</tt> if suspended
	 */
	boolean isWriteSuspended();
}
