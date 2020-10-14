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
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.transport.socket.nio.NioSession;
import org.apache.mina.util.ExceptionMonitor;

/**
 * A default implementation of {@link IoFilterChain} that provides all operations
 * for developers who want to implement their own transport layer once used with {@link AbstractIoSession}.
 */
public final class DefaultIoFilterChain implements IoFilterChain {
	/**
	 * A session attribute that stores an {@link IoFuture} related with the {@link IoSession}.
	 * {@link DefaultIoFilterChain} clears this attribute and notifies the future
	 * when {@link #fireSessionCreated()} or {@link #fireExceptionCaught(Throwable)} is invoked.
	 */
	public static final Object SESSION_CREATED_FUTURE = DefaultIoFilterChain.class;

	/** The associated session */
	private final NioSession session;

	/** The chain head */
	private EntryImpl head;

	/** The chain tail */
	private EntryImpl tail;

	/**
	 * Create a new default chain, associated with a session.
	 * It will only contain a HeadFilter and a TailFilter.
	 *
	 * @param session The session associated with the created filter chain
	 */
	public DefaultIoFilterChain(NioSession session) {
		if (session == null)
			throw new IllegalArgumentException("session");
		this.session = session;
	}

	@Override
	public NioSession getSession() {
		return session;
	}

	@Override
	public synchronized EntryImpl getEntry(String name) {
		for (EntryImpl e = head; e != null; e = e.nextEntry) {
			if (e.getName().equals(name))
				return e;
		}
		return null;
	}

	@Override
	public synchronized EntryImpl getEntry(IoFilter filter) {
		for (EntryImpl e = head; e != null; e = e.nextEntry) {
			if (e.getFilter() == filter)
				return e;
		}
		return null;
	}

	@Override
	public synchronized EntryImpl getEntry(Class<? extends IoFilter> filterType) {
		for (EntryImpl e = head; e != null; e = e.nextEntry) {
			if (filterType.isAssignableFrom(e.getFilter().getClass()))
				return e;
		}
		return null;
	}

	@Override
	public synchronized ArrayList<Entry> getAll() {
		ArrayList<Entry> list = new ArrayList<>();
		for (EntryImpl e = head; e != null; e = e.nextEntry)
			list.add(e);
		return list;
	}

	@Override
	public synchronized ArrayList<Entry> getAllReversed() {
		ArrayList<Entry> list = new ArrayList<>();
		for (EntryImpl e = tail; e != null; e = e.prevEntry)
			list.add(e);
		return list;
	}

	@Override
	public synchronized void addFirst(String name, IoFilter filter) {
		checkAddable(name);
		register(null, head, name, filter);
	}

	@Override
	public synchronized void addLast(String name, IoFilter filter) {
		checkAddable(name);
		register(tail, null, name, filter);
	}

	@Override
	public synchronized void addBefore(String baseName, String name, IoFilter filter) {
		EntryImpl baseEntry = checkOldName(baseName);
		checkAddable(name);
		register(baseEntry.prevEntry, baseEntry, name, filter);
	}

	@Override
	public synchronized void addAfter(String baseName, String name, IoFilter filter) {
		EntryImpl baseEntry = checkOldName(baseName);
		checkAddable(name);
		register(baseEntry, baseEntry.nextEntry, name, filter);
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
		for (EntryImpl entry; (entry = tail) != null;) {
			try {
				deregister(entry);
			} catch (Exception e) {
				throw new RuntimeException("clear: " + entry.getName() + " in " + getSession(), e);
			}
		}
	}

	/**
	 * Register the newly added filter, inserting it between the previous and
	 * the next filter in the filter's chain. We also call the preAdd and postAdd methods.
	 */
	private void register(EntryImpl prevEntry, EntryImpl nextEntry, String name, IoFilter filter) {
		EntryImpl newEntry = new EntryImpl(prevEntry, nextEntry, name, filter);

		try {
			filter.onPreAdd(this, name, newEntry);
		} catch (Exception e) {
			throw new RuntimeException("onPreAdd: " + name + ':' + filter + " in " + getSession(), e);
		}

		if (prevEntry != null)
			prevEntry.nextEntry = newEntry;
		else
			head = newEntry;

		if (nextEntry != null)
			nextEntry.prevEntry = newEntry;
		else
			tail = newEntry;

		try {
			filter.onPostAdd(this, name, newEntry);
		} catch (Exception e) {
			deregister0(newEntry);
			throw new RuntimeException("onPostAdd: " + name + ':' + filter + " in " + getSession(), e);
		}
	}

	private void deregister(EntryImpl entry) {
		IoFilter filter = entry.getFilter();

		try {
			filter.onPreRemove(this, entry.getName(), entry);
		} catch (Exception e) {
			throw new RuntimeException("onPreRemove: " + entry.getName() + ':' + filter + " in " + getSession(), e);
		}

		deregister0(entry);

		try {
			filter.onPostRemove(this, entry.getName(), entry);
		} catch (Exception e) {
			throw new RuntimeException("onPostRemove: " + entry.getName() + ':' + filter + " in " + getSession(), e);
		}
	}

	private void deregister0(EntryImpl entry) {
		EntryImpl prevEntry = entry.prevEntry;
		EntryImpl nextEntry = entry.nextEntry;
		if (prevEntry != null)
			prevEntry.nextEntry = nextEntry;
		else
			head = nextEntry;
		if (nextEntry != null)
			nextEntry.prevEntry = prevEntry;
		else
			tail = prevEntry;
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
		if (e == null)
			throw new IllegalArgumentException("filter not found: " + baseName);
		return e;
	}

	/**
	 * Checks the specified filter name is already taken and throws an exception if already taken.
	 */
	private void checkAddable(String name) {
		if (getEntry(name) != null) {
			throw new IllegalArgumentException("other filter is using the same name: " + name);
		}
	}

	@Override
	public void fireSessionCreated() {
		try {
			callNextSessionCreated(head);
		} catch (Exception e) {
			fireExceptionCaught(e);
		}
	}

	private void callNextSessionCreated(Entry entry) throws Exception {
		if (entry != null) {
			entry.getFilter().sessionCreated(entry.getNextFilter(), session);
		} else {
			try {
				session.getHandler().sessionCreated(session);
			} finally {
				ConnectFuture future = (ConnectFuture)session.removeAttribute(SESSION_CREATED_FUTURE);
				if (future != null)
					future.setValue(session);
			}
		}
	}

	@Override
	public void fireSessionOpened() {
		try {
			callNextSessionOpened(head);
		} catch (Exception e) {
			fireExceptionCaught(e);
		}
	}

	private void callNextSessionOpened(Entry entry) throws Exception {
		if (entry != null)
			entry.getFilter().sessionOpened(entry.getNextFilter(), session);
		else
			session.getHandler().sessionOpened(session);
	}

	@Override
	public void fireSessionClosed() {
		try {
			session.getCloseFuture().setClosed();
		} catch (Exception e) {
			fireExceptionCaught(e);
		}
		try {
			callNextSessionClosed(head);
		} catch (Exception e) {
			fireExceptionCaught(e);
		}
	}

	private void callNextSessionClosed(Entry entry) throws Exception {
		if (entry != null)
			entry.getFilter().sessionClosed(entry.getNextFilter(), session);
		else {
			session.getHandler().sessionClosed(session);
			session.getFilterChain().clear();
		}
	}

	@Override
	public void fireExceptionCaught(Throwable cause) {
		ConnectFuture future = (ConnectFuture)session.removeAttribute(SESSION_CREATED_FUTURE);
		if (future == null) {
			try {
				callNextExceptionCaught(head, cause);
			} catch (Exception e) {
				ExceptionMonitor.getInstance().exceptionCaught(e);
			}
		} else {
			// Please note that this place is not the only place that calls ConnectFuture.setException().
			session.closeNow();
			future.setException(cause);
		}
	}

	private void callNextExceptionCaught(Entry entry, Throwable cause) throws Exception {
		if (entry != null)
			entry.getFilter().exceptionCaught(entry.getNextFilter(), session, cause);
		else
			session.getHandler().exceptionCaught(session, cause);
	}

	@Override
	public void fireInputClosed() throws Exception {
		callNextInputClosed(head);
	}

	private void callNextInputClosed(Entry entry) throws Exception {
		if (entry != null)
			entry.getFilter().inputClosed(entry.getNextFilter(), session);
		else
			session.getHandler().inputClosed(session);
	}

	@Override
	public void fireMessageReceived(Object message) throws Exception {
		callNextMessageReceived(head, message);
	}

	private void callNextMessageReceived(Entry entry, Object message) throws Exception {
		if (entry != null)
			entry.getFilter().messageReceived(entry.getNextFilter(), session, message);
		else
			session.getHandler().messageReceived(session, message);
	}

	@Override
	public void fireFilterWrite(WriteRequest writeRequest) {
		try {
			callPreviousFilterWrite(tail, writeRequest);
		} catch (Exception e) {
			writeRequest.writeRequestFuture().setException(e);
			fireExceptionCaught(e);
		}
	}

	private void callPreviousFilterWrite(Entry entry, WriteRequest writeRequest) throws Exception {
		if (entry != null)
			entry.getFilter().filterWrite(entry.getNextFilter(), session, writeRequest);
		else
			session.getProcessor().write(session, writeRequest);
	}

	@Override
	public void fireFilterClose() {
		try {
			callPreviousFilterClose(tail);
		} catch (Exception e) {
			fireExceptionCaught(e);
		} finally {
			session.getProcessor().remove(session);
		}
	}

	private void callPreviousFilterClose(Entry entry) throws Exception {
		if (entry != null)
			entry.getFilter().filterClose(entry.getNextFilter(), session);
	}

	@Override
	public synchronized String toString() {
		StringBuilder buf = new StringBuilder().append('{');
		boolean empty = true;

		for (EntryImpl e = head; e != null; e = e.nextEntry) {
			if (!empty)
				buf.append(", ");
			else
				empty = false;

			buf.append('(').append(e.getName()).append(':').append(e.getFilter()).append(')');
		}

		if (empty)
			buf.append("empty");

		return buf.append('}').toString();
	}

	private final class EntryImpl implements Entry, NextFilter {
		EntryImpl prevEntry;
		EntryImpl nextEntry;
		private final String name;
		private final IoFilter filter;

		EntryImpl(EntryImpl prevEntry, EntryImpl nextEntry, String name, IoFilter filter) {
			if (name == null)
				throw new IllegalArgumentException("name");
			if (filter == null)
				throw new IllegalArgumentException("filter");

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
			sb.append(getName());

			// Add the previous filter
			sb.append("', prev:'");

			if (prevEntry != null)
				sb.append(prevEntry.name).append(':').append(prevEntry.getFilter());
			else
				sb.append("null");

			// Add the next filter
			sb.append("', next:'");

			if (nextEntry != null)
				sb.append(nextEntry.name).append(':').append(nextEntry.getFilter());
			else
				sb.append("null");

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
		public void sessionCreated() throws Exception {
			callNextSessionCreated(nextEntry);
		}

		@Override
		public void sessionOpened() throws Exception {
			callNextSessionOpened(nextEntry);
		}

		@Override
		public void messageReceived(Object message) throws Exception {
			callNextMessageReceived(nextEntry, message);
		}

		@Override
		public void filterWrite(WriteRequest writeRequest) throws Exception {
			callPreviousFilterWrite(prevEntry, writeRequest);
		}

		@Override
		public void filterClose() throws Exception {
			callPreviousFilterClose(prevEntry);
		}

		@Override
		public void inputClosed() throws Exception {
			callNextInputClosed(nextEntry);
		}

		@Override
		public void sessionClosed() throws Exception {
			callNextSessionClosed(nextEntry);
		}

		@Override
		public void exceptionCaught(Throwable cause) throws Exception {
			callNextExceptionCaught(nextEntry, cause);
		}
	}
}
