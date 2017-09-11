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
package org.apache.mina.core.service;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.mina.core.filterchain.IoFilterChain;
import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.session.IoSession;

/**
 * A helper class which provides addition and removal of {@link IoServiceListener}s and firing events.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public final class IoServiceListenerSupport {
	/** The {@link IoService} that this instance manages. */
	private final IoService service;

	/** Tracks managed sessions. */
	private final ConcurrentMap<Long, IoSession> managedSessions = new ConcurrentHashMap<>();

	/**  Read only version of {@link #managedSessions}. */
	private final Map<Long, IoSession> readOnlyManagedSessions = Collections.unmodifiableMap(managedSessions);

	private final AtomicBoolean activated = new AtomicBoolean();

	/**
	 * Creates a new instance of the listenerSupport.
	 *
	 * @param service The associated IoService
	 */
	public IoServiceListenerSupport(IoService service) {
		if (service == null) {
			throw new IllegalArgumentException("service");
		}

		this.service = service;
	}

	/**
	 * @return A Map of the managed {@link IoSession}s
	 */
	public Map<Long, IoSession> getManagedSessions() {
		return readOnlyManagedSessions;
	}

	/**
	 * @return The number of managed {@link IoSession}s
	 */
	public int getManagedSessionCount() {
		return managedSessions.size();
	}

	/**
	 * @return true if the instance is active
	 */
	public boolean isActive() {
		return activated.get();
	}

	/**
	 * Calls {@link IoServiceListener#serviceActivated(IoService)} for all registered listeners.
	 */
	public void fireServiceActivated() {
		activated.set(true);
	}

	/**
	 * Calls {@link IoServiceListener#serviceDeactivated(IoService)} for all registered listeners.
	 */
	public void fireServiceDeactivated() {
		if (!activated.compareAndSet(true, false)) {
			// The instance is already desactivated
			return;
		}

		disconnectSessions();
	}

	/**
	 * Calls {@link IoServiceListener#sessionCreated(IoSession)} for all registered listeners.
	 *
	 * @param session The session which has been created
	 */
	public void fireSessionCreated(IoSession session) {
		boolean firstSession = false;

		if (session.getService() instanceof IoConnector) {
			firstSession = managedSessions.isEmpty();
		}

		// If already registered, ignore.
		if (managedSessions.putIfAbsent(session.getId(), session) != null) {
			return;
		}

		// If the first connector session, fire a virtual service activation event.
		if (firstSession) {
			fireServiceActivated();
		}

		// Fire session events.
		IoFilterChain filterChain = session.getFilterChain();
		filterChain.fireSessionCreated();
		filterChain.fireSessionOpened();
	}

	/**
	 * Calls {@link IoServiceListener#sessionDestroyed(IoSession)} for all registered listeners.
	 *
	 * @param session The session which has been destroyed
	 */
	public void fireSessionDestroyed(IoSession session) {
		// Try to remove the remaining empty session set after removal.
		if (managedSessions.remove(session.getId()) == null) {
			return;
		}

		// Fire session events.
		session.getFilterChain().fireSessionClosed();

		// Fire a virtual service deactivation event for the last session of the connector.
		if (session.getService() instanceof IoConnector) {
			if (managedSessions.isEmpty()) {
				fireServiceDeactivated();
			}
		}
	}

	/**
	 * Close all the sessions
	 *
	 */
	private void disconnectSessions() {
		if (!(service instanceof IoAcceptor)) {
			// We don't disconnect sessions for anything but an Acceptor
			return;
		}

		if (!((IoAcceptor) service).isCloseOnDeactivation()) {
			return;
		}

		Object lock = new Object();
		IoFutureListener<IoFuture> listener = new LockNotifyingListener(lock);

		for (IoSession s : managedSessions.values()) {
			s.closeNow().addListener(listener);
		}

		try {
			synchronized (lock) {
				while (!managedSessions.isEmpty()) {
					lock.wait(500);
				}
			}
		} catch (InterruptedException ie) {
			// Ignored
		}
	}

	/**
	 * A listener in charge of releasing the lock when the close has been completed
	 */
	private static final class LockNotifyingListener implements IoFutureListener<IoFuture> {
		private final Object lock;

		public LockNotifyingListener(Object lock) {
			this.lock = lock;
		}

		@Override
		public void operationComplete(IoFuture future) {
			synchronized (lock) {
				lock.notifyAll();
			}
		}
	}
}
