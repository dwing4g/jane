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
package org.apache.mina.core.buffer;

import java.nio.ByteBuffer;

/**
 * A simplistic {@link IoBufferAllocator} which simply allocates a new
 * buffer every time.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public final class SimpleBufferAllocator implements IoBufferAllocator {
	@Override
	public IoBuffer allocate(int capacity, boolean direct) {
		return wrap(allocateNioBuffer(capacity, direct));
	}

	@Override
	public ByteBuffer allocateNioBuffer(int capacity, boolean direct) {
		return direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
	}

	@Override
	public IoBuffer wrap(ByteBuffer nioBuffer) {
		return new SimpleBuffer(nioBuffer);
	}

	@Override
	public void dispose() {
		// Do nothing
	}

	private static final class SimpleBuffer extends AbstractIoBuffer {
		private ByteBuffer buf;

		private SimpleBuffer(ByteBuffer bb) {
			super(bb.capacity());
			this.buf = bb;
		}

		private SimpleBuffer(SimpleBuffer parent, ByteBuffer bb) {
			super(parent);
			this.buf = bb;
		}

		@Override
		public ByteBuffer buf() {
			return buf;
		}

		@Override
		protected void buf(ByteBuffer bb) {
			buf = bb;
		}

		@Override
		protected IoBuffer duplicate0() {
			return new SimpleBuffer(this, buf.duplicate());
		}

		@Override
		protected IoBuffer slice0() {
			return new SimpleBuffer(this, buf.slice());
		}

		@Override
		public byte[] array() {
			return buf.array();
		}

		@Override
		public int arrayOffset() {
			return buf.arrayOffset();
		}

		@Override
		public boolean hasArray() {
			return buf.hasArray();
		}

		@Override
		public void free() {
			// Do nothing
		}
	}
}
