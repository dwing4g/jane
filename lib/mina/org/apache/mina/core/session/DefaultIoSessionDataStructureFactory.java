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

import java.io.IOException;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.core.write.WriteRequestQueue;
import org.apache.mina.transport.socket.nio.NioSession;

/**
 * The default {@link IoSessionDataStructureFactory} implementation
 * that creates a new {@link HashMap}-based {@link IoSessionAttributeMap}
 * instance and a new synchronized {@link ConcurrentLinkedQueue} instance per {@link IoSession}.
 */
public class DefaultIoSessionDataStructureFactory implements IoSessionDataStructureFactory {
	@Override
	public IoSessionAttributeMap getAttributeMap(IoSession session) {
		return new DefaultIoSessionAttributeMap();
	}

	@Override
	public WriteRequestQueue getWriteRequestQueue(IoSession session) {
		return new DefaultWriteRequestQueue(session);
	}

	private static final class DefaultIoSessionAttributeMap implements IoSessionAttributeMap {
		private final ConcurrentHashMap<Object, Object> attributes = new ConcurrentHashMap<>(4);

		@Override
		public Object getAttribute(Object key, Object defaultValue) {
			Object value = attributes.get(key);
			return value != null ? value : defaultValue;
		}

		@Override
		public Object setAttribute(Object key, Object value) {
			return value != null ? attributes.put(key, value) : attributes.remove(key);
		}

		@Override
		public Object setAttributeIfAbsent(Object key, Object value) {
			return value != null ? attributes.putIfAbsent(key, value) : null;
		}

		@Override
		public Object removeAttribute(Object key) {
			return attributes.remove(key);
		}

		@Override
		public boolean removeAttribute(Object key, Object value) {
			return attributes.remove(key, value);
		}

		@Override
		public boolean replaceAttribute(Object key, Object oldValue, Object newValue) {
			return attributes.replace(key, oldValue, newValue);
		}

		@Override
		public boolean containsAttribute(Object key) {
			return attributes.containsKey(key);
		}

		@Override
		public Set<Object> getAttributeKeys() {
			return attributes.keySet();
		}

		@Override
		public void dispose() throws Exception {
		}
	}

	private static final class DefaultWriteRequestQueue implements WriteRequestQueue {
		/** A queue to store incoming write requests */
		private final ConcurrentLinkedQueue<WriteRequest> q = new ConcurrentLinkedQueue<>();
		private final IoSession s;

		DefaultWriteRequestQueue(IoSession session) {
			s = session;
		}

		@Override
		public void dispose() {
		}

		@Override
		public void clear() {
			q.clear();
		}

		@Override
		public boolean isEmpty() {
			return q.isEmpty();
		}

		@Override
		public void offer(WriteRequest writeRequest) {
			q.offer(writeRequest);
		}

		@Override
		public WriteRequest poll() {
			WriteRequest wr = q.poll();

			if (wr == NioSession.CLOSE_REQUEST) {
				s.closeNow();
				dispose();
				wr = null;
			} else if (wr == NioSession.SHUTDOWN_REQUEST) {
				try {
					((NioSession)s).getChannel().shutdownOutput();
				} catch(IOException e) {
				}
				dispose();
				wr = null;
			}

			return wr;
		}

		@Override
		public String toString() {
			return q.toString();
		}

		@Override
		public int size() {
			return q.size();
		}
	}
}
