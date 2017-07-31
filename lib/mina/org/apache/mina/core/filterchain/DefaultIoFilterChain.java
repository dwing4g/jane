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
 *
 */
package org.apache.mina.core.filterchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.mina.core.filterchain.IoFilter.NextFilter;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.session.AbstractIoSession;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A default implementation of {@link IoFilterChain} that provides
 * all operations for developers who want to implement their own
 * transport layer once used with {@link AbstractIoSession}.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public final class DefaultIoFilterChain implements IoFilterChain {
	/** The logger for this class */
	private static final Logger LOGGER = LoggerFactory.getLogger(DefaultIoFilterChain.class);

	/**
	 * A session attribute that stores an {@link IoFuture} related with
	 * the {@link IoSession}.  {@link DefaultIoFilterChain} clears this
	 * attribute and notifies the future when {@link #fireSessionCreated()}
	 * or {@link #fireExceptionCaught(Throwable)} is invoked.
	 */
	public static final String SESSION_CREATED_FUTURE = "DefaultIoFilterChain.connectFuture";

	/** The associated session */
	private final AbstractIoSession session;

	/** The mapping between the filters and their associated name */
	private final Map<String, Entry> name2entry = new ConcurrentHashMap<>();

	/** The chain head */
	private final EntryImpl head;

	/** The chain tail */
	private final EntryImpl tail;

	/**
	 * Create a new default chain, associated with a session. It will only contain a
	 * HeadFilter and a TailFilter.
	 *
	 * @param session The session associated with the created filter chain
	 */
	public DefaultIoFilterChain(AbstractIoSession session) {
		if (session == null) {
			throw new IllegalArgumentException("session");
		}

		this.session = session;
		head = new EntryImpl(null, null, "head", new HeadFilter());
		tail = new EntryImpl(head, null, "tail", new TailFilter());
		head.nextEntry = tail;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IoSession getSession() {
		return session;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Entry getEntry(String name) {
		Entry e = name2entry.get(name);

		if (e == null) {
			return null;
		}

		return e;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Entry getEntry(IoFilter filter) {
		EntryImpl e = head.nextEntry;

		while (e != tail) {
			if (e.getFilter() == filter) {
				return e;
			}

			e = e.nextEntry;
		}

		return null;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Entry getEntry(Class<? extends IoFilter> filterType) {
		EntryImpl e = head.nextEntry;

		while (e != tail) {
			if (filterType.isAssignableFrom(e.getFilter().getClass())) {
				return e;
			}

			e = e.nextEntry;
		}

		return null;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IoFilter get(String name) {
		Entry e = getEntry(name);

		if (e == null) {
			return null;
		}

		return e.getFilter();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IoFilter get(Class<? extends IoFilter> filterType) {
		Entry e = getEntry(filterType);

		if (e == null) {
			return null;
		}

		return e.getFilter();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public NextFilter getNextFilter(String name) {
		Entry e = getEntry(name);

		if (e == null) {
			return null;
		}

		return e.getNextFilter();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public NextFilter getNextFilter(IoFilter filter) {
		Entry e = getEntry(filter);

		if (e == null) {
			return null;
		}

		return e.getNextFilter();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public NextFilter getNextFilter(Class<? extends IoFilter> filterType) {
		Entry e = getEntry(filterType);

		if (e == null) {
			return null;
		}

		return e.getNextFilter();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public synchronized void addFirst(String name, IoFilter filter) {
		checkAddable(name);
		register(head, name, filter);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public synchronized void addLast(String name, IoFilter filter) {
		checkAddable(name);
		register(tail.prevEntry, name, filter);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public synchronized void addBefore(String baseName, String name, IoFilter filter) {
		EntryImpl baseEntry = checkOldName(baseName);
		checkAddable(name);
		register(baseEntry.prevEntry, name, filter);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public synchronized void addAfter(String baseName, String name, IoFilter filter) {
		EntryImpl baseEntry = checkOldName(baseName);
		checkAddable(name);
		register(baseEntry, name, filter);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public synchronized IoFilter remove(String name) {
		EntryImpl entry = checkOldName(name);
		deregister(entry);

		return entry.getFilter();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public synchronized void remove(IoFilter filter) {
		EntryImpl e = head.nextEntry;

		while (e != tail) {
			if (e.getFilter() == filter) {
				deregister(e);

				return;
			}

			e = e.nextEntry;
		}

		throw new IllegalArgumentException("Filter not found: " + filter.getClass().getName());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public synchronized IoFilter remove(Class<? extends IoFilter> filterType) {
		EntryImpl e = head.nextEntry;

		while (e != tail) {
			if (filterType.isAssignableFrom(e.getFilter().getClass())) {
				IoFilter oldFilter = e.getFilter();
				deregister(e);

				return oldFilter;
			}

			e = e.nextEntry;
		}

		throw new IllegalArgumentException("Filter not found: " + filterType.getName());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public synchronized IoFilter replace(String name, IoFilter newFilter) {
		EntryImpl entry = checkOldName(name);
		IoFilter oldFilter = entry.getFilter();

		// Call the preAdd method of the new filter
		try {
			newFilter.onPreAdd(this, name, entry.getNextFilter());
		} catch (Exception e) {
			throw new IoFilterLifeCycleException("onPreAdd(): " + name + ':' + newFilter + " in " + getSession(), e);
		}

		// Now, register the new Filter replacing the old one.
		entry.setFilter(newFilter);

		// Call the postAdd method of the new filter
		try {
			newFilter.onPostAdd(this, name, entry.getNextFilter());
		} catch (Exception e) {
			entry.setFilter(oldFilter);
			throw new IoFilterLifeCycleException("onPostAdd(): " + name + ':' + newFilter + " in " + getSession(), e);
		}

		return oldFilter;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public synchronized void replace(IoFilter oldFilter, IoFilter newFilter) {
		EntryImpl entry = head.nextEntry;

		// Search for the filter to replace
		while (entry != tail) {
			if (entry.getFilter() == oldFilter) {
				String oldFilterName = null;

				// Get the old filter name. It's not really efficient...
				for (Map.Entry<String, Entry> mapping : name2entry.entrySet()) {
					if (entry == mapping.getValue() ) {
						oldFilterName = mapping.getKey();

						break;
					}
				}

				// Call the preAdd method of the new filter
				try {
					newFilter.onPreAdd(this, oldFilterName, entry.getNextFilter());
				} catch (Exception e) {
					throw new IoFilterLifeCycleException("onPreAdd(): " + oldFilterName + ':' + newFilter + " in "
							+ getSession(), e);
				}

				// Now, register the new Filter replacing the old one.
				entry.setFilter(newFilter);

				// Call the postAdd method of the new filter
				try {
					newFilter.onPostAdd(this, oldFilterName, entry.getNextFilter());
				} catch (Exception e) {
					entry.setFilter(oldFilter);
					throw new IoFilterLifeCycleException("onPostAdd(): " + oldFilterName + ':' + newFilter + " in "
							+ getSession(), e);
				}

				return;
			}

			entry = entry.nextEntry;
		}

		throw new IllegalArgumentException("Filter not found: " + oldFilter.getClass().getName());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public synchronized IoFilter replace(Class<? extends IoFilter> oldFilterType, IoFilter newFilter) {
		EntryImpl entry = head.nextEntry;

		while (entry != tail) {
			if (oldFilterType.isAssignableFrom(entry.getFilter().getClass())) {
				IoFilter oldFilter = entry.getFilter();

				String oldFilterName = null;

				// Get the old filter name. It's not really efficient...
				for (Map.Entry<String, Entry> mapping : name2entry.entrySet()) {
					if (entry == mapping.getValue() ) {
						oldFilterName = mapping.getKey();

						break;
					}
				}

				// Call the preAdd method of the new filter
				try {
					newFilter.onPreAdd(this, oldFilterName, entry.getNextFilter());
				} catch (Exception e) {
					throw new IoFilterLifeCycleException("onPreAdd(): " + oldFilterName + ':' + newFilter + " in "
							+ getSession(), e);
				}

				entry.setFilter(newFilter);

				// Call the postAdd method of the new filter
				try {
					newFilter.onPostAdd(this, oldFilterName, entry.getNextFilter());
				} catch (Exception e) {
					entry.setFilter(oldFilter);
					throw new IoFilterLifeCycleException("onPostAdd(): " + oldFilterName + ':' + newFilter + " in "
							+ getSession(), e);
				}

				return oldFilter;
			}

			entry = entry.nextEntry;
		}

		throw new IllegalArgumentException("Filter not found: " + oldFilterType.getName());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public synchronized void clear() throws Exception {
		List<IoFilterChain.Entry> l = new ArrayList<>(name2entry.values());

		for (IoFilterChain.Entry entry : l) {
			try {
				deregister((EntryImpl) entry);
			} catch (Exception e) {
				throw new IoFilterLifeCycleException("clear(): " + entry.getName() + " in " + getSession(), e);
			}
		}
	}

	/**
	 * Register the newly added filter, inserting it between the previous and
	 * the next filter in the filter's chain. We also call the preAdd and
	 * postAdd methods.
	 */
	private void register(EntryImpl prevEntry, String name, IoFilter filter) {
		EntryImpl newEntry = new EntryImpl(prevEntry, prevEntry.nextEntry, name, filter);

		try {
			filter.onPreAdd(this, name, newEntry.getNextFilter());
		} catch (Exception e) {
			throw new IoFilterLifeCycleException("onPreAdd(): " + name + ':' + filter + " in " + getSession(), e);
		}

		prevEntry.nextEntry.prevEntry = newEntry;
		prevEntry.nextEntry = newEntry;
		name2entry.put(name, newEntry);

		try {
			filter.onPostAdd(this, name, newEntry.getNextFilter());
		} catch (Exception e) {
			deregister0(newEntry);
			throw new IoFilterLifeCycleException("onPostAdd(): " + name + ':' + filter + " in " + getSession(), e);
		}
	}

	private void deregister(EntryImpl entry) {
		IoFilter filter = entry.getFilter();

		try {
			filter.onPreRemove(this, entry.getName(), entry.getNextFilter());
		} catch (Exception e) {
			throw new IoFilterLifeCycleException("onPreRemove(): " + entry.getName() + ':' + filter + " in "
					+ getSession(), e);
		}

		deregister0(entry);

		try {
			filter.onPostRemove(this, entry.getName(), entry.getNextFilter());
		} catch (Exception e) {
			throw new IoFilterLifeCycleException("onPostRemove(): " + entry.getName() + ':' + filter + " in "
					+ getSession(), e);
		}
	}

	private void deregister0(EntryImpl entry) {
		EntryImpl prevEntry = entry.prevEntry;
		EntryImpl nextEntry = entry.nextEntry;
		prevEntry.nextEntry = nextEntry;
		nextEntry.prevEntry = prevEntry;

		name2entry.remove(entry.name);
	}

	/**
	 * Throws an exception when the specified filter name is not registered in this chain.
	 *
	 * @return An filter entry with the specified name.
	 */
	private EntryImpl checkOldName(String baseName) {
		EntryImpl e = (EntryImpl) name2entry.get(baseName);

		if (e == null) {
			throw new IllegalArgumentException("Filter not found:" + baseName);
		}

		return e;
	}

	/**
	 * Checks the specified filter name is already taken and throws an exception if already taken.
	 */
	private void checkAddable(String name) {
		if (name2entry.containsKey(name)) {
			throw new IllegalArgumentException("Other filter is using the same name '" + name + "'");
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void fireSessionCreated() {
		callNextSessionCreated(head, session);
	}

	private void callNextSessionCreated(Entry entry, IoSession ioSession) {
		try {
			entry.getFilter().sessionCreated(entry.getNextFilter(), ioSession);
		} catch (Exception e) {
			fireExceptionCaught(e);
		} catch (Error e) {
			fireExceptionCaught(e);
			throw e;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void fireSessionOpened() {
		callNextSessionOpened(head, session);
	}

	private void callNextSessionOpened(Entry entry, IoSession ioSession) {
		try {
			entry.getFilter().sessionOpened(entry.getNextFilter(), ioSession);
		} catch (Exception e) {
			fireExceptionCaught(e);
		} catch (Error e) {
			fireExceptionCaught(e);
			throw e;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void fireSessionClosed() {
		// Update future.
		try {
			session.getCloseFuture().setClosed();
		} catch (Exception e) {
			fireExceptionCaught(e);
		} catch (Error e) {
			fireExceptionCaught(e);
			throw e;
		}

		// And start the chain.
		callNextSessionClosed(head, session);
	}

	private void callNextSessionClosed(Entry entry, IoSession ioSession) {
		try {
			entry.getFilter().sessionClosed(entry.getNextFilter(), ioSession);
		} catch (Exception | Error e) {
			fireExceptionCaught(e);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void fireMessageReceived(Object message) {
		callNextMessageReceived(head, session, message);
	}

	private void callNextMessageReceived(Entry entry, IoSession ioSession, Object message) {
		try {
			entry.getFilter().messageReceived(entry.getNextFilter(), ioSession, message);
		} catch (Exception e) {
			fireExceptionCaught(e);
		} catch (Error e) {
			fireExceptionCaught(e);
			throw e;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void fireMessageSent(WriteRequest request) {
		try {
			request.getFuture().setWritten();
		} catch (Exception e) {
			fireExceptionCaught(e);
		} catch (Error e) {
			fireExceptionCaught(e);
			throw e;
		}
	}

	/**
	 * {@inheritDoc}
	 */
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
				LOGGER.error("Unexpected exception from exceptionCaught handler.", e);
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

	/**
	 * {@inheritDoc}
	 */
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

	/**
	 * {@inheritDoc}
	 */
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
		} catch (Error e) {
			writeRequest.getFuture().setException(e);
			fireExceptionCaught(e);
			throw e;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void fireFilterClose() {
		callPreviousFilterClose(tail, session);
	}

	private void callPreviousFilterClose(Entry entry, IoSession ioSession) {
		try {
			entry.getFilter().filterClose(entry.getNextFilter(), ioSession);
		} catch (Exception e) {
			fireExceptionCaught(e);
		} catch (Error e) {
			fireExceptionCaught(e);
			throw e;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<Entry> getAll() {
		List<Entry> list = new ArrayList<>();
		EntryImpl e = head.nextEntry;

		while (e != tail) {
			list.add(e);
			e = e.nextEntry;
		}

		return list;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<Entry> getAllReversed() {
		List<Entry> list = new ArrayList<>();
		EntryImpl e = tail.prevEntry;

		while (e != head) {
			list.add(e);
			e = e.prevEntry;
		}

		return list;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean contains(String name) {
		return getEntry(name) != null;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean contains(IoFilter filter) {
		return getEntry(filter) != null;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean contains(Class<? extends IoFilter> filterType) {
		return getEntry(filterType) != null;
	}

	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder();
		buf.append("{ ");

		boolean empty = true;

		EntryImpl e = head.nextEntry;

		while (e != tail) {
			if (!empty) {
				buf.append(", ");
			} else {
				empty = false;
			}

			buf.append('(');
			buf.append(e.getName());
			buf.append(':');
			buf.append(e.getFilter());
			buf.append(')');

			e = e.nextEntry;
		}

		if (empty) {
			buf.append("empty");
		}

		buf.append(" }");

		return buf.toString();
	}

	private static final class HeadFilter extends IoFilterAdapter {
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
			AbstractIoSession s = (AbstractIoSession) session;

			// Propagate the message
			session.getHandler().messageReceived(s, message);
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

	private final class EntryImpl implements Entry {
		private EntryImpl prevEntry;

		private EntryImpl nextEntry;

		private final String name;

		private IoFilter filter;

		private final NextFilter nextFilter;

		private EntryImpl(EntryImpl prevEntry, EntryImpl nextEntry, String name, IoFilter filter) {
			if (filter == null) {
				throw new IllegalArgumentException("filter");
			}

			if (name == null) {
				throw new IllegalArgumentException("name");
			}

			this.prevEntry = prevEntry;
			this.nextEntry = nextEntry;
			this.name = name;
			this.filter = filter;
			this.nextFilter = new NextFilter() {
				/**
				 * {@inheritDoc}
				 */
				@Override
				public void sessionCreated(IoSession ioSession) {
					callNextSessionCreated(EntryImpl.this.nextEntry, ioSession);
				}

				/**
				 * {@inheritDoc}
				 */
				@Override
				public void sessionOpened(IoSession ioSession) {
					callNextSessionOpened(EntryImpl.this.nextEntry, ioSession);
				}

				/**
				 * {@inheritDoc}
				 */
				@Override
				public void sessionClosed(IoSession ioSession) {
					callNextSessionClosed(EntryImpl.this.nextEntry, ioSession);
				}

				/**
				 * {@inheritDoc}
				 */
				@Override
				public void exceptionCaught(IoSession ioSession, Throwable cause) {
					callNextExceptionCaught(EntryImpl.this.nextEntry, ioSession, cause);
				}

				/**
				 * {@inheritDoc}
				 */
				@Override
				public void inputClosed(IoSession ioSession) {
					callNextInputClosed(EntryImpl.this.nextEntry, ioSession);
				}

				/**
				 * {@inheritDoc}
				 */
				@Override
				public void messageReceived(IoSession ioSession, Object message) {
					callNextMessageReceived(EntryImpl.this.nextEntry, ioSession, message);
				}

				/**
				 * {@inheritDoc}
				 */
				@Override
				public void filterWrite(IoSession ioSession, WriteRequest writeRequest) {
					callPreviousFilterWrite(EntryImpl.this.prevEntry, ioSession, writeRequest);
				}

				/**
				 * {@inheritDoc}
				 */
				@Override
				public void filterClose(IoSession ioSession) {
					callPreviousFilterClose(EntryImpl.this.prevEntry, ioSession);
				}

				/**
				 * {@inheritDoc}
				 */
				@Override
				public String toString() {
					return EntryImpl.this.nextEntry.name;
				}
			};
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public String getName() {
			return name;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public IoFilter getFilter() {
			return filter;
		}

		private void setFilter(IoFilter filter) {
			if (filter == null) {
				throw new IllegalArgumentException("filter");
			}

			this.filter = filter;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public NextFilter getNextFilter() {
			return nextFilter;
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();

			// Add the current filter
			sb.append("('").append(getName()).append('\'');

			// Add the previous filter
			sb.append(", prev: '");

			if (prevEntry != null) {
				sb.append(prevEntry.name);
				sb.append(':');
				sb.append(prevEntry.getFilter().getClass().getSimpleName());
			} else {
				sb.append("null");
			}

			// Add the next filter
			sb.append("', next: '");

			if (nextEntry != null) {
				sb.append(nextEntry.name);
				sb.append(':');
				sb.append(nextEntry.getFilter().getClass().getSimpleName());
			} else {
				sb.append("null");
			}

			sb.append("')");

			return sb.toString();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void addAfter(String name1, IoFilter filter1) {
			DefaultIoFilterChain.this.addAfter(getName(), name1, filter1);
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void addBefore(String name1, IoFilter filter1) {
			DefaultIoFilterChain.this.addBefore(getName(), name1, filter1);
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void remove() {
			DefaultIoFilterChain.this.remove(getName());
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void replace(IoFilter newFilter) {
			DefaultIoFilterChain.this.replace(getName(), newFilter);
		}
	}
}
