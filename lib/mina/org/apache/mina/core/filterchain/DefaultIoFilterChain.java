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

import java.util.ArrayList;
import org.apache.mina.core.filterchain.IoFilter.NextFilter;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.session.AbstractIoSession;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.util.ExceptionMonitor;

/**
 * A default implementation of {@link IoFilterChain} that provides all operations
 * for developers who want to implement their own transport layer once used with {@link AbstractIoSession}.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public final class DefaultIoFilterChain implements IoFilterChain {
	/**
	 * A session attribute that stores an {@link IoFuture} related with the {@link IoSession}.
	 * {@link DefaultIoFilterChain} clears this attribute and notifies the future
	 * when {@link #fireSessionCreated()} or {@link #fireExceptionCaught(Throwable)} is invoked.
	 */
	public static final Object SESSION_CREATED_FUTURE = DefaultIoFilterChain.class;

	/** The associated session */
	private final AbstractIoSession session;

	/** The chain head */
	private final EntryImpl head = new EntryImpl(null, null, "head", HeadFilter.instance);

	/** The chain tail */
	private final EntryImpl tail = new EntryImpl(head, null, "tail", TailFilter.instance);

	/**
	 * Create a new default chain, associated with a session.
	 * It will only contain a HeadFilter and a TailFilter.
	 *
	 * @param session The session associated with the created filter chain
	 */
	public DefaultIoFilterChain(AbstractIoSession session) {
		if (session == null) {
			throw new IllegalArgumentException("session");
		}

		this.session = session;
		head.nextEntry = tail;
	}

	@Override
	public IoSession getSession() {
		return session;
	}

	@Override
	public synchronized EntryImpl getEntry(String name) {
		for (EntryImpl e = head.nextEntry; e != tail; e = e.nextEntry) {
			if (e.getName().equals(name)) {
				return e;
			}
		}
		return null;
	}

	@Override
	public synchronized EntryImpl getEntry(IoFilter filter) {
		for (EntryImpl e = head.nextEntry; e != tail; e = e.nextEntry) {
			if (e.getFilter() == filter) {
				return e;
			}
		}
		return null;
	}

	@Override
	public synchronized EntryImpl getEntry(Class<? extends IoFilter> filterType) {
		for (EntryImpl e = head.nextEntry; e != tail; e = e.nextEntry) {
			if (filterType.isAssignableFrom(e.getFilter().getClass())) {
				return e;
			}
		}
		return null;
	}

	@Override
	public synchronized void addFirst(String name, IoFilter filter) {
		checkAddable(name);
		register(head, name, filter);
	}

	@Override
	public synchronized void addLast(String name, IoFilter filter) {
		checkAddable(name);
		register(tail.prevEntry, name, filter);
	}

	@Override
	public synchronized void addBefore(String baseName, String name, IoFilter filter) {
		EntryImpl baseEntry = checkOldName(baseName);
		checkAddable(name);
		register(baseEntry.prevEntry, name, filter);
	}

	@Override
	public synchronized void addAfter(String baseName, String name, IoFilter filter) {
		EntryImpl baseEntry = checkOldName(baseName);
		checkAddable(name);
		register(baseEntry, name, filter);
	}

	@Override
	public synchronized boolean remove(Entry filter) {
		if (filter instanceof EntryImpl) {
			deregister((EntryImpl)filter);
			return true;
		}
		return false;
	}

	@Override
	public synchronized void clear() {
		for (EntryImpl entry; (entry = tail.prevEntry) != head;) {
			try {
				deregister(entry);
			} catch (Exception e) {
				throw new RuntimeException("clear(): " + entry.getName() + " in " + getSession(), e);
			}
		}
	}

	/**
	 * Register the newly added filter, inserting it between the previous and
	 * the next filter in the filter's chain. We also call the preAdd and postAdd methods.
	 */
	private void register(EntryImpl prevEntry, String name, IoFilter filter) {
		EntryImpl newEntry = new EntryImpl(prevEntry, prevEntry.nextEntry, name, filter);

		try {
			filter.onPreAdd(this, name, newEntry);
		} catch (Exception e) {
			throw new RuntimeException("onPreAdd(): " + name + ':' + filter + " in " + getSession(), e);
		}

		prevEntry.nextEntry.prevEntry = newEntry;
		prevEntry.nextEntry = newEntry;

		try {
			filter.onPostAdd(this, name, newEntry);
		} catch (Exception e) {
			deregister0(newEntry);
			throw new RuntimeException("onPostAdd(): " + name + ':' + filter + " in " + getSession(), e);
		}
	}

	private void deregister(EntryImpl entry) {
		IoFilter filter = entry.getFilter();

		try {
			filter.onPreRemove(this, entry.getName(), entry);
		} catch (Exception e) {
			throw new RuntimeException("onPreRemove(): " + entry.getName() + ':' + filter + " in " + getSession(), e);
		}

		deregister0(entry);

		try {
			filter.onPostRemove(this, entry.getName(), entry);
		} catch (Exception e) {
			throw new RuntimeException("onPostRemove(): " + entry.getName() + ':' + filter + " in " + getSession(), e);
		}
	}

	private static void deregister0(EntryImpl entry) {
		EntryImpl prevEntry = entry.prevEntry;
		EntryImpl nextEntry = entry.nextEntry;
		prevEntry.nextEntry = nextEntry;
		nextEntry.prevEntry = prevEntry;
		entry.prevEntry = null;
		entry.nextEntry = null;
	}

	/**
	 * Throws an exception when the specified filter name is not registered in this chain.
	 *
	 * @return An filter entry with the specified name.
	 */
	private EntryImpl checkOldName(String baseName) {
		EntryImpl e = getEntry(baseName);
		if (e == null) {
			throw new IllegalArgumentException("Filter not found: " + baseName);
		}

		return e;
	}

	/**
	 * Checks the specified filter name is already taken and throws an exception if already taken.
	 */
	private void checkAddable(String name) {
		if (getEntry(name) != null) {
			throw new IllegalArgumentException("Other filter is using the same name: " + name);
		}
	}

	@Override
	public void fireSessionCreated() {
		callNextSessionCreated(head, session);
	}

	private void callNextSessionCreated(Entry entry, IoSession ioSession) {
		try {
			entry.getFilter().sessionCreated(entry.getNextFilter(), ioSession);
		} catch (Exception e) {
			fireExceptionCaught(e);
		} catch (Throwable e) {
			fireExceptionCaught(e);
			throw e;
		}
	}

	@Override
	public void fireSessionOpened() {
		callNextSessionOpened(head, session);
	}

	private void callNextSessionOpened(Entry entry, IoSession ioSession) {
		try {
			entry.getFilter().sessionOpened(entry.getNextFilter(), ioSession);
		} catch (Exception e) {
			fireExceptionCaught(e);
		} catch (Throwable e) {
			fireExceptionCaught(e);
			throw e;
		}
	}

	@Override
	public void fireSessionClosed() {
		// Update future.
		try {
			session.getCloseFuture().setClosed();
		} catch (Exception e) {
			fireExceptionCaught(e);
		} catch (Throwable e) {
			fireExceptionCaught(e);
			throw e;
		}

		// And start the chain.
		callNextSessionClosed(head, session);
	}

	private void callNextSessionClosed(Entry entry, IoSession ioSession) {
		try {
			entry.getFilter().sessionClosed(entry.getNextFilter(), ioSession);
		} catch (Throwable e) {
			fireExceptionCaught(e);
		}
	}

	@Override
	public void fireMessageReceived(Object message) {
		callNextMessageReceived(head, session, message);
	}

	private void callNextMessageReceived(Entry entry, IoSession ioSession, Object message) {
		try {
			entry.getFilter().messageReceived(entry.getNextFilter(), ioSession, message);
		} catch (Exception e) {
			fireExceptionCaught(e);
		} catch (Throwable e) {
			fireExceptionCaught(e);
			throw e;
		}
	}

	@Override
	public void fireMessageSent(WriteRequest request) {
		try {
			request.getFuture().setWritten();
		} catch (Exception e) {
			fireExceptionCaught(e);
		} catch (Throwable e) {
			fireExceptionCaught(e);
			throw e;
		}
	}

	@Override
	public void fireExceptionCaught(Throwable cause) {
		callNextExceptionCaught(head, session, cause);
	}

	private static void callNextExceptionCaught(Entry entry, IoSession ioSession, Throwable cause) {
		// Notify the related future.
		ConnectFuture future = (ConnectFuture) ioSession.removeAttribute(SESSION_CREATED_FUTURE);
		if (future == null) {
			try {
				entry.getFilter().exceptionCaught(entry.getNextFilter(), ioSession, cause);
			} catch (Throwable e) {
				ExceptionMonitor.getInstance().exceptionCaught(e);
			}
		} else {
			// Please note that this place is not the only place that
			// calls ConnectFuture.setException().
			if (!ioSession.isClosing()) {
				// Call the closeNow method only if needed
				ioSession.closeNow();
			}

			future.setException(cause);
		}
	}

	@Override
	public void fireInputClosed() {
		callNextInputClosed(head, session);
	}

	private void callNextInputClosed(Entry entry, IoSession ioSession) {
		try {
			entry.getFilter().inputClosed(entry.getNextFilter(), ioSession);
		} catch (Throwable e) {
			fireExceptionCaught(e);
		}
	}

	@Override
	public void fireFilterWrite(WriteRequest writeRequest) {
		callPreviousFilterWrite(tail, session, writeRequest);
	}

	private void callPreviousFilterWrite(Entry entry, IoSession ioSession, WriteRequest writeRequest) {
		try {
			entry.getFilter().filterWrite(entry.getNextFilter(), ioSession, writeRequest);
		} catch (Exception e) {
			writeRequest.getFuture().setException(e);
			fireExceptionCaught(e);
		} catch (Throwable e) {
			writeRequest.getFuture().setException(e);
			fireExceptionCaught(e);
			throw e;
		}
	}

	@Override
	public void fireFilterClose() {
		callPreviousFilterClose(tail, session);
	}

	private void callPreviousFilterClose(Entry entry, IoSession ioSession) {
		try {
			entry.getFilter().filterClose(entry.getNextFilter(), ioSession);
		} catch (Exception e) {
			fireExceptionCaught(e);
		} catch (Throwable e) {
			fireExceptionCaught(e);
			throw e;
		}
	}

	@Override
	public synchronized ArrayList<Entry> getAll() {
		ArrayList<Entry> list = new ArrayList<>();

		for (EntryImpl e = head.nextEntry; e != tail; e = e.nextEntry) {
			list.add(e);
		}

		return list;
	}

	@Override
	public synchronized ArrayList<Entry> getAllReversed() {
		ArrayList<Entry> list = new ArrayList<>();

		for (EntryImpl e = tail.prevEntry; e != head; e = e.prevEntry) {
			list.add(e);
		}

		return list;
	}

	@Override
	public synchronized String toString() {
		StringBuilder buf = new StringBuilder("{ ");

		boolean empty = true;

		for (EntryImpl e = head.nextEntry; e != tail; e = e.nextEntry) {
			if (!empty) {
				buf.append(", ");
			} else {
				empty = false;
			}

			buf.append('(').append(e.getName()).append(':').append(e.getFilter()).append(')');
		}

		if (empty) {
			buf.append("empty");
		}

		return buf.append(" }").toString();
	}

	private static final class HeadFilter extends IoFilterAdapter {
		private static final HeadFilter instance = new HeadFilter();

		@SuppressWarnings("unchecked")
		@Override
		public void filterWrite(NextFilter nextFilter, IoSession ioSession, WriteRequest writeRequest) throws Exception {
			((AbstractIoSession) ioSession).getProcessor().write(ioSession, writeRequest);
		}

		@SuppressWarnings("unchecked")
		@Override
		public void filterClose(NextFilter nextFilter, IoSession ioSession) throws Exception {
			((AbstractIoSession) ioSession).getProcessor().remove(ioSession);
		}
	}

	private static final class TailFilter extends IoFilterAdapter {
		private static final TailFilter instance = new TailFilter();

		@Override
		public void sessionCreated(NextFilter nextFilter, IoSession session) throws Exception {
			try {
				session.getHandler().sessionCreated(session);
			} finally {
				// Notify the related future.
				ConnectFuture future = (ConnectFuture) session.removeAttribute(SESSION_CREATED_FUTURE);
				if (future != null) {
					future.setSession(session);
				}
			}
		}

		@Override
		public void sessionOpened(NextFilter nextFilter, IoSession session) throws Exception {
			session.getHandler().sessionOpened(session);
		}

		@Override
		public void sessionClosed(NextFilter nextFilter, IoSession session) throws Exception {
			AbstractIoSession s = (AbstractIoSession) session;

			try {
				s.getHandler().sessionClosed(session);
			} finally {
				try {
					s.getWriteRequestQueue().dispose();
				} finally {
					try {
						s.getAttributeMap().dispose();
					} finally {
						// Remove all filters.
						session.getFilterChain().clear();
					}
				}
			}
		}

		@Override
		public void exceptionCaught(NextFilter nextFilter, IoSession session, Throwable cause) throws Exception {
			AbstractIoSession s = (AbstractIoSession) session;
			s.getHandler().exceptionCaught(s, cause);
		}

		@Override
		public void inputClosed(NextFilter nextFilter, IoSession session) throws Exception {
			session.getHandler().inputClosed(session);
		}

		@Override
		public void messageReceived(NextFilter nextFilter, IoSession session, Object message) throws Exception {
			session.getHandler().messageReceived(session, message);
		}

		@Override
		public void filterWrite(NextFilter nextFilter, IoSession session, WriteRequest writeRequest) throws Exception {
			nextFilter.filterWrite(session, writeRequest);
		}

		@Override
		public void filterClose(NextFilter nextFilter, IoSession session) throws Exception {
			nextFilter.filterClose(session);
		}
	}

	private final class EntryImpl implements Entry, NextFilter {
		private EntryImpl prevEntry;
		private EntryImpl nextEntry;
		private final String name;
		private final IoFilter filter;

		private EntryImpl(EntryImpl prevEntry, EntryImpl nextEntry, String name, IoFilter filter) {
			if (name == null) {
				throw new IllegalArgumentException("name");
			}
			if (filter == null) {
				throw new IllegalArgumentException("filter");
			}

			this.prevEntry = prevEntry;
			this.nextEntry = nextEntry;
			this.name = name;
			this.filter = filter;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public IoFilter getFilter() {
			return filter;
		}

		@Override
		public NextFilter getNextFilter() {
			return this;
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder("('");

			// Add the current filter
			sb.append(getName()).append('\'');

			// Add the previous filter
			sb.append(", prev: '");

			if (prevEntry != null) {
				sb.append(prevEntry.name).append(':').append(prevEntry.getFilter().getClass().getSimpleName());
			} else {
				sb.append("null");
			}

			// Add the next filter
			sb.append("', next: '");

			if (nextEntry != null) {
				sb.append(nextEntry.name).append(':').append(nextEntry.getFilter().getClass().getSimpleName());
			} else {
				sb.append("null");
			}

			return sb.append("')").toString();
		}

		@Override
		public void addAfter(String name1, IoFilter filter1) {
			DefaultIoFilterChain.this.addAfter(getName(), name1, filter1);
		}

		@Override
		public void addBefore(String name1, IoFilter filter1) {
			DefaultIoFilterChain.this.addBefore(getName(), name1, filter1);
		}

		@Override
		public void remove() {
			DefaultIoFilterChain.this.remove(this);
		}

		@Override
		public void sessionCreated(IoSession ioSession) {
			callNextSessionCreated(nextEntry, ioSession);
		}

		@Override
		public void sessionOpened(IoSession ioSession) {
			callNextSessionOpened(nextEntry, ioSession);
		}

		@Override
		public void sessionClosed(IoSession ioSession) {
			callNextSessionClosed(nextEntry, ioSession);
		}

		@Override
		public void exceptionCaught(IoSession ioSession, Throwable cause) {
			callNextExceptionCaught(nextEntry, ioSession, cause);
		}

		@Override
		public void inputClosed(IoSession ioSession) {
			callNextInputClosed(nextEntry, ioSession);
		}

		@Override
		public void messageReceived(IoSession ioSession, Object message) {
			callNextMessageReceived(nextEntry, ioSession, message);
		}

		@Override
		public void filterWrite(IoSession ioSession, WriteRequest writeRequest) {
			callPreviousFilterWrite(prevEntry, ioSession, writeRequest);
		}

		@Override
		public void filterClose(IoSession ioSession) {
			callPreviousFilterClose(prevEntry, ioSession);
		}
	}
}
