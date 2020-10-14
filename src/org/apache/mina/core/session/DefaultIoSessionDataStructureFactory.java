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

import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.core.write.WriteRequestQueue;

/**
 * The default {@link IoSessionDataStructureFactory} implementation
 * that creates a new {@link HashMap}-based {@link IoSessionAttributeMap}
 * instance and a new synchronized {@link ConcurrentLinkedQueue} instance per {@link IoSession}.
 */
public class DefaultIoSessionDataStructureFactory implements IoSessionDataStructureFactory {
	public static final DefaultIoSessionDataStructureFactory instance = new DefaultIoSessionDataStructureFactory();

	@Override
	public IoSessionAttributeMap getAttributeMap(IoSession session) {
		return new DefaultIoSessionAttributeMap();
	}

	@Override
	public WriteRequestQueue getWriteRequestQueue(IoSession session) {
		return new DefaultWriteRequestQueue();
	}

	private static final class DefaultIoSessionAttributeMap extends ConcurrentHashMap<Object, Object> implements IoSessionAttributeMap {
		private static final long serialVersionUID = 1L;

		DefaultIoSessionAttributeMap() {
			super(4);
		}

		@Override
		public Object getAttribute(Object key, Object defaultValue) {
			Object value = get(key);
			return value != null ? value : defaultValue;
		}

		@Override
		public Object setAttribute(Object key, Object value) {
			return value != null ? put(key, value) : remove(key);
		}

		@Override
		public Object setAttributeIfAbsent(Object key, Object value) {
			return value != null ? putIfAbsent(key, value) : null;
		}

		@Override
		public Object removeAttribute(Object key) {
			return remove(key);
		}

		@Override
		public boolean removeAttribute(Object key, Object value) {
			return remove(key, value);
		}

		@Override
		public boolean replaceAttribute(Object key, Object oldValue, Object newValue) {
			return replace(key, oldValue, newValue);
		}

		@Override
		public boolean containsAttribute(Object key) {
			return containsKey(key);
		}

		@Override
		public Set<Object> getAttributeKeys() {
			return keySet();
		}
	}

	private static final class DefaultWriteRequestQueue extends ConcurrentLinkedQueue<WriteRequest> implements WriteRequestQueue {
		private static final long serialVersionUID = 1L;
	}
}
