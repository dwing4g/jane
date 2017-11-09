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
package org.apache.mina.core.buffer;

import java.nio.ByteBuffer;

/**
 * A simplistic singleton {@link IoBufferAllocator} which simply allocates a new buffer every time.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public final class SimpleBufferAllocator implements IoBufferAllocator {
	public static final SimpleBufferAllocator instance = new SimpleBufferAllocator();
	public static final IoBuffer emptyBuffer = new SimpleIoBuffer(0, false);
	public static final IoBuffer emptyDirectBuffer = new SimpleIoBuffer(0, true);

	private SimpleBufferAllocator() {
	}

	@Override
	public IoBuffer allocate(int capacity, boolean direct) {
		if (capacity <= 0) {
			return direct ? emptyDirectBuffer : emptyBuffer;
		}
		return new SimpleIoBuffer(capacity, direct);
	}

	@Override
	public IoBuffer wrap(ByteBuffer bb) {
		return new SimpleIoBuffer(bb);
	}

	@Override
	public void dispose() {
	}

	private static final class SimpleIoBuffer extends IoBuffer {
		private final ByteBuffer buf;

		private SimpleIoBuffer(int capacity, boolean direct) {
			buf = (direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity));
		}

		private SimpleIoBuffer(ByteBuffer bb) {
			buf = bb;
		}

		@Override
		public ByteBuffer buf() {
			return buf;
		}

		@Override
		public IoBuffer duplicate() {
			return new SimpleIoBuffer(buf.duplicate());
		}

		@Override
		public void free() {
		}
	}
}
