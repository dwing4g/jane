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
import java.util.Collections;
import java.util.ListIterator;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.mina.core.filterchain.IoFilter.NextFilter;
import org.apache.mina.core.filterchain.IoFilterChain.Entry;
import org.apache.mina.core.session.IoSession;

/**
 * The default implementation of {@link IoFilterChainBuilder} which is useful
 * in most cases.  {@link DefaultIoFilterChainBuilder} has an similar interface
 * with {@link IoFilterChain}; it contains a list of {@link IoFilter}s that you can
 * modify. The {@link IoFilter}s which are added to this builder will be appended
 * to the {@link IoFilterChain} when {@link #buildFilterChain(IoFilterChain)} is invoked.
 * <p>
 * However, the similar interface doesn't mean that it behaves in an exactly
 * same way with {@link IoFilterChain}.  {@link DefaultIoFilterChainBuilder}
 * doesn't manage the life cycle of the {@link IoFilter}s at all, and the
 * existing {@link IoSession}s won't get affected by the changes in this builder.
 * {@link IoFilterChainBuilder}s affect only newly created {@link IoSession}s.
 *
 * <pre>
 * IoAcceptor acceptor = ...;
 * DefaultIoFilterChainBuilder builder = acceptor.getFilterChain();
 * builder.addLast("myFilter", new MyFilter());
 * ...
 * </pre>
 */
public final class DefaultIoFilterChainBuilder implements IoFilterChainBuilder {
	/** The list of filters */
	private final CopyOnWriteArrayList<Entry> entries;

	/**
	 * Creates a new instance with an empty filter list.
	 */
	public DefaultIoFilterChainBuilder() {
		this(null);
	}

	/**
	 * Creates a new copy of the specified {@link DefaultIoFilterChainBuilder}.
	 *
	 * @param filterChain The FilterChain we will copy
	 */
	public DefaultIoFilterChainBuilder(DefaultIoFilterChainBuilder filterChain) {
		entries = (filterChain != null ? new CopyOnWriteArrayList<>(filterChain.entries) : new CopyOnWriteArrayList<>());
	}

	/**
	 * @see IoFilterChain#getEntry(String)
	 *
	 * @param name The Filter's name we are looking for
	 * @return The found Entry
	 */
	public Entry getEntry(String name) {
		for (Entry e : entries) {
			if (e.getName().equals(name))
				return e;
		}

		return null;
	}

	/**
	 * @see IoFilterChain#getEntry(IoFilter)
	 *
	 * @param filter The Filter we are looking for
	 * @return The found Entry
	 */
	public Entry getEntry(IoFilter filter) {
		for (Entry e : entries) {
			if (e.getFilter() == filter)
				return e;
		}

		return null;
	}

	/**
	 * @see IoFilterChain#getEntry(Class)
	 *
	 * @param filterType The FilterType we are looking for
	 * @return The found Entry
	 */
	public Entry getEntry(Class<? extends IoFilter> filterType) {
		for (Entry e : entries) {
			if (filterType.isAssignableFrom(e.getFilter().getClass()))
				return e;
		}

		return null;
	}

	/**
	 * @see IoFilterChain#getAll()
	 *
	 * @return The list of Filters
	 */
	public ArrayList<Entry> getAll() {
		return new ArrayList<>(entries);
	}

	/**
	 * @see IoFilterChain#getAllReversed()
	 *
	 * @return The list of Filters, reversed
	 */
	public ArrayList<Entry> getAllReversed() {
		ArrayList<Entry> result = getAll();
		Collections.reverse(result);
		return result;
	}

	/**
	 * @see IoFilterChain#addFirst(String, IoFilter)
	 *
	 * @param name The filter's name
	 * @param filter The filter to add
	 */
	public synchronized void addFirst(String name, IoFilter filter) {
		register(0, new EntryImpl(name, filter));
	}

	/**
	 * @see IoFilterChain#addLast(String, IoFilter)
	 *
	 * @param name The filter's name
	 * @param filter The filter to add
	 */
	public synchronized void addLast(String name, IoFilter filter) {
		register(entries.size(), new EntryImpl(name, filter));
	}

	/**
	 * @see IoFilterChain#addBefore(String, String, IoFilter)
	 *
	 * @param baseName The filter baseName
	 * @param name The filter's name
	 * @param filter The filter to add
	 */
	public synchronized void addBefore(String baseName, String name, IoFilter filter) {
		for (ListIterator<Entry> it = entries.listIterator(); it.hasNext();) {
			if (it.next().getName().equals(baseName)) {
				register(it.previousIndex(), new EntryImpl(name, filter));
				return;
			}
		}

		throw new IllegalArgumentException("unknown filter baseName: " + baseName);
	}

	/**
	 * @see IoFilterChain#addAfter(String, String, IoFilter)
	 *
	 * @param baseName The filter baseName
	 * @param name The filter's name
	 * @param filter The filter to add
	 */
	public synchronized void addAfter(String baseName, String name, IoFilter filter) {
		for (ListIterator<Entry> it = entries.listIterator(); it.hasNext();) {
			if (it.next().getName().equals(baseName)) {
				register(it.nextIndex(), new EntryImpl(name, filter));
				return;
			}
		}

		throw new IllegalArgumentException("unknown filter baseName: " + baseName);
	}

	/**
	 * @see IoFilterChain#remove(String)
	 *
	 * @param name The Filter's name to remove from the list of Filters
	 * @return The removed IoFilter
	 */
	public synchronized IoFilter remove(String name) {
		for (ListIterator<Entry> it = entries.listIterator(); it.hasNext();) {
			Entry e = it.next();
			if (e.getName().equals(name)) {
				entries.remove(it.previousIndex());
				return e.getFilter();
			}
		}

		throw new IllegalArgumentException("unknown filter name: " + name);
	}

	/**
	 * Replace a filter by a new one.
	 *
	 * @param name The name of the filter to replace
	 * @param newFilter The new filter to use
	 * @return The replaced filter
	 */
	public synchronized IoFilter replace(String name, IoFilter newFilter) {
		EntryImpl e = (EntryImpl)getEntry(name);
		if (e == null)
			throw new IllegalArgumentException("unknown filter name: " + name);
		IoFilter oldFilter = e.filter;
		e.filter = newFilter;
		return oldFilter;
	}

	/**
	 * @see IoFilterChain#clear()
	 */
	public synchronized void clear() {
		entries.clear();
	}

	@Override
	public void buildFilterChain(IoFilterChain chain) throws Exception {
		for (Entry e : entries)
			chain.addLast(e.getName(), e.getFilter());
	}

	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder("{ ");
		boolean empty = true;

		for (Entry e : entries) {
			if (!empty)
				buf.append(", ");
			else
				empty = false;

			buf.append('(').append(e.getName()).append(':').append(e.getFilter()).append(')');
		}

		if (empty)
			buf.append("empty");

		return buf.append(" }").toString();
	}

	private void register(int index, Entry e) {
		if (getEntry(e.getName()) != null)
			throw new IllegalArgumentException("other filter is using the same name: " + e.getName());

		entries.add(index, e);
	}

	private final class EntryImpl implements Entry {
		private final String name;
		volatile IoFilter filter;

		EntryImpl(String name, IoFilter filter) {
			if (name == null)
				throw new IllegalArgumentException("name");
			if (filter == null)
				throw new IllegalArgumentException("filter");

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
			throw new IllegalStateException();
		}

		@Override
		public void addAfter(String name1, IoFilter filter1) {
			DefaultIoFilterChainBuilder.this.addAfter(name, name1, filter1);
		}

		@Override
		public void addBefore(String name1, IoFilter filter1) {
			DefaultIoFilterChainBuilder.this.addBefore(name, name1, filter1);
		}

		@Override
		public void remove() {
			DefaultIoFilterChainBuilder.this.remove(name);
		}

		@Override
		public String toString() {
			return '(' + name + ':' + filter + ')';
		}
	}
}
