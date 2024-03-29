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
package org.apache.mina.core.future;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.apache.mina.core.service.IoProcessor;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.transport.socket.nio.NioProcessor;
import org.apache.mina.util.ExceptionMonitor;

/** A default implementation of {@link IoFuture} associated with an {@link IoSession}. */
public class DefaultIoFuture implements IoFuture {
	/** A number of milliseconds to wait between two deadlock controls ( 5 seconds ) */
	private static final long DEAD_LOCK_CHECK_INTERVAL = 5000L;

	/** The associated session */
	private final IoSession session;

	/** The first listener. This is easier to have this variable when we most of the time have one single listener */
	private IoFutureListener<?> firstListener;

	/** All the other listeners, in case we have more than one */
	private ArrayList<IoFutureListener<?>> otherListeners;

	private Object result;

	/** A counter for the number of threads waiting on this future. highest bit is used to determinate if completed */
	private int waiters;

	/**
	 * Creates a new instance associated with an {@link IoSession}.
	 *
	 * @param session an {@link IoSession} which is associated with this future
	 */
	public DefaultIoFuture(IoSession session) {
		this.session = session;
	}

	@Override
	public IoSession getSession() {
		return session;
	}

	@Override
	public IoFuture await() throws InterruptedException {
		synchronized (this) {
			while (waiters >= 0) {
				waiters++;
				try {
					// Wait for a notify, or if no notify is called,
					// assume that we have a deadlock and exit the loop to check for a potential deadlock.
					wait(DEAD_LOCK_CHECK_INTERVAL);
				} finally {
					if (--waiters >= 0)
						checkDeadLock();
				}
			}
		}
		return this;
	}

	@Override
	public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
		return await0(unit.toMillis(timeout), true);
	}

	@Override
	public boolean await(long timeoutMillis) throws InterruptedException {
		return await0(timeoutMillis, true);
	}

	@Override
	public IoFuture awaitUninterruptibly() {
		try {
			await0(Long.MAX_VALUE, false);
		} catch (InterruptedException ie) {
			// Do nothing: this catch is just mandatory by contract
		}

		return this;
	}

	@Override
	public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
		try {
			return await0(unit.toMillis(timeout), false);
		} catch (InterruptedException e) {
			throw new InternalError();
		}
	}

	@Override
	public boolean awaitUninterruptibly(long timeoutMillis) {
		try {
			return await0(timeoutMillis, false);
		} catch (InterruptedException e) {
			throw new InternalError();
		}
	}

	/**
	 * Wait for the Future to be ready. If the requested delay is 0 or negative,
	 * this method immediately returns the value of the 'ready' flag.
	 * Every 5 second, the wait will be suspended to be able to check if there is a deadlock or not.
	 *
	 * @param timeoutMillis The delay we will wait for the Future to be ready
	 * @param interruptable Tells if the wait can be interrupted or not
	 * @return <tt>true</tt> if the Future is ready
	 * @throws InterruptedException If the thread has been interrupted when it's not allowed.
	 */
	private boolean await0(long timeoutMillis, boolean interruptable) throws InterruptedException {
		long endTime = System.currentTimeMillis() + timeoutMillis;
		if (endTime < 0)
			endTime = Long.MAX_VALUE;

		synchronized (this) {
			// We can quit if the ready flag is set to true, or if
			// the timeout is set to 0 or below: we don't wait in this case.
			if (waiters < 0 || timeoutMillis <= 0)
				return waiters < 0;

			// The operation is not completed: we have to wait
			waiters++;

			try {
				for (; ; ) {
					try {
						// Wait for the requested period of time,
						// but every DEAD_LOCK_CHECK_INTERVAL seconds, we will check that we aren't blocked.
						wait(Math.min(timeoutMillis, DEAD_LOCK_CHECK_INTERVAL));
					} catch (InterruptedException e) {
						if (interruptable)
							throw e;
					}

					if (waiters < 0 || endTime < System.currentTimeMillis())
						return waiters < 0;
					// Take a chance, detect a potential deadlock
					checkDeadLock();
				}
			} finally {
				// We get here for 3 possible reasons :
				// 1) We have been notified (the operation has completed a way or another)
				// 2) We have reached the timeout
				// 3) The thread has been interrupted
				// In any case, we decrement the number of waiters, and we get out.
				if (--waiters >= 0)
					checkDeadLock();
			}
		}
	}

	/** Check for a deadlock, ie look into the stack trace that we don't have already an instance of the caller. */
	private void checkDeadLock() {
		// Only read / write / connect / write future can cause dead lock.
		if (!(this instanceof CloseFuture || this instanceof WriteFuture || this instanceof ConnectFuture))
			return;

		// Get the current thread stackTrace.
		// Using Thread.currentThread().getStackTrace() is the best solution,
		// even if slightly less efficient than doing a new Exception().getStackTrace(),
		// as internally, it does exactly the same thing. The advantage of using
		// this solution is that we may benefit some improvement with some future versions of Java.
		StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

		// Simple and quick check.
		for (StackTraceElement s : stackTrace) {
			if (NioProcessor.class.getName().equals(s.getClassName())) {
				// new IllegalStateException("t").getStackTrace();
				throw new IllegalStateException("DEAD LOCK: " + IoFuture.class.getSimpleName()
						+ ".await() was invoked from an I/O processor thread. Please use "
						+ IoFutureListener.class.getSimpleName() + " or configure a proper thread model alternatively");
			}
		}

		// And then more precisely.
		for (StackTraceElement s : stackTrace) {
			try {
				Class<?> cls = DefaultIoFuture.class.getClassLoader().loadClass(s.getClassName());

				if (IoProcessor.class.isAssignableFrom(cls)) {
					throw new IllegalStateException("DEAD LOCK: " + IoFuture.class.getSimpleName()
							+ ".await() was invoked from an I/O processor thread. Please use "
							+ IoFutureListener.class.getSimpleName() + " or configure a proper thread model alternatively");
				}
			} catch (ClassNotFoundException ignored) {
			}
		}
	}

	@Override
	public synchronized boolean isDone() {
		return waiters < 0;
	}

	/** @return the result of the asynchronous operation. */
	public synchronized Object getValue() {
		return result;
	}

	/**
	 * Sets the result of the asynchronous operation, and mark it as finished.
	 *
	 * @param newValue The result to store into the Future
	 * @return {@code true} if the value has been set, {@code false} if
	 * 		the future already has a value (thus is in ready state)
	 */
	public boolean setValue(Object newValue) {
		synchronized (this) {
			// Allowed only once.
			int w = waiters;
			if (w < 0)
				return false;

			result = newValue;
			waiters = w | 0x8000_0000;

			// Now, if we have waiters, notify them that the operation has completed
			if (w > 0)
				notifyAll();
		}

		// Last, not least, inform the listeners
		notifyListeners();

		return true;
	}

	@Override
	public IoFuture addListener(IoFutureListener<?> listener) {
		if (listener == null)
			throw new IllegalArgumentException("listener");

		synchronized (this) {
			if (waiters < 0) {
				// Shortcut: if the operation has completed, no need to add a new listener, we just have to notify it.
				// The existing listeners have already been notified anyway, when the 'ready' flag has been set.
				notifyListener(listener);
			} else {
				if (firstListener == null)
					firstListener = listener;
				else {
					if (otherListeners == null)
						otherListeners = new ArrayList<>(1);
					otherListeners.add(listener);
				}
			}
		}

		return this;
	}

	@Override
	public IoFuture removeListener(IoFutureListener<?> listener) {
		if (listener == null)
			throw new IllegalArgumentException("listener");

		synchronized (this) {
			if (waiters >= 0) {
				if (listener == firstListener) {
					if ((otherListeners != null) && !otherListeners.isEmpty())
						firstListener = otherListeners.remove(0);
					else
						firstListener = null;
				} else if (otherListeners != null)
					otherListeners.remove(listener);
			}
		}

		return this;
	}

	/** Notify the listeners, if we have some. */
	private void notifyListeners() {
		// There won't be any visibility problem or concurrent modification
		// because 'ready' flag will be checked against both addListener and removeListener calls.
		if (firstListener != null) {
			notifyListener(firstListener);
			firstListener = null;

			if (otherListeners != null) {
				for (int i = 0, n = otherListeners.size(); i < n; i++)
					notifyListener(otherListeners.get(i));
				otherListeners = null;
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void notifyListener(@SuppressWarnings("rawtypes") IoFutureListener listener) {
		try {
			listener.operationComplete(this);
		} catch (Exception e) {
			ExceptionMonitor.getInstance().exceptionCaught(e);
		}
	}
}
