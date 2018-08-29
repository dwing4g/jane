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
package org.apache.mina.core.write;

import java.util.concurrent.TimeUnit;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.IoSession;

/**
 * The default implementation of {@link WriteRequest}.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public final class DefaultWriteRequest implements WriteRequest {
	/** An empty FUTURE */
	public static final WriteFuture UNUSED_FUTURE = new WriteFuture() {
		@Override
		public boolean isWritten() {
			return false;
		}

		@Override
		public void setWritten() {
			// Do nothing
		}

		@Override
		public IoSession getSession() {
			return null;
		}

		@Override
		public boolean isDone() {
			return true;
		}

		@Override
		public WriteFuture addListener(IoFutureListener<?> listener) {
			throw new IllegalStateException("You can't add a listener to a dummy future.");
		}

		@Override
		public WriteFuture removeListener(IoFutureListener<?> listener) {
			throw new IllegalStateException("You can't add a listener to a dummy future.");
		}

		@Override
		public WriteFuture await() throws InterruptedException {
			return this;
		}

		@Override
		public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
			return true;
		}

		@Override
		public boolean await(long timeoutMillis) throws InterruptedException {
			return true;
		}

		@Override
		public WriteFuture awaitUninterruptibly() {
			return this;
		}

		@Override
		public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
			return true;
		}

		@Override
		public boolean awaitUninterruptibly(long timeoutMillis) {
			return true;
		}

		@Override
		public Throwable getException() {
			return null;
		}

		@Override
		public void setException(Throwable cause) {
			// Do nothing
		}
	};

	private final Object message;
	private final WriteFuture future;

	/**
	 * @param message a message to write
	 * @param future a future that needs to be notified when an operation is finished
	 */
	public DefaultWriteRequest(Object message, WriteFuture future) {
		if (message == null) {
			throw new IllegalArgumentException("message");
		}

		this.message = message;
		this.future = (future != null ? future : UNUSED_FUTURE);
	}

	@Override
	public final Object writeRequestMessage() {
		return message;
	}

	@Override
	public final WriteFuture writeRequestFuture() {
		return future;
	}

	@Override
	public String toString() {
		return "WriteRequest: " + message;
	}
}
