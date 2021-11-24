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

import org.apache.mina.core.session.IoSession;

/** A default implementation of {@link WriteFuture}. */
public final class DefaultWriteFuture extends DefaultIoFuture implements WriteFuture {
	/** @param session The associated session */
	public DefaultWriteFuture(IoSession session) {
		super(session);
	}

	/**
	 * Returns a new {@link DefaultWriteFuture} which is already marked as 'written'.
	 *
	 * @param session The associated session
	 * @return A new future for a written message
	 */
	public static WriteFuture newWrittenFuture(IoSession session) {
		DefaultWriteFuture writtenFuture = new DefaultWriteFuture(session);
		writtenFuture.setWritten();
		return writtenFuture;
	}

	/**
	 * Returns a new {@link DefaultWriteFuture} which is already marked as 'not written'.
	 *
	 * @param session The associated session
	 * @param cause   The reason why the message has not be written
	 * @return A new future for not written message
	 */
	public static WriteFuture newNotWrittenFuture(IoSession session, Throwable cause) {
		DefaultWriteFuture unwrittenFuture = new DefaultWriteFuture(session);
		unwrittenFuture.setException(cause);
		return unwrittenFuture;
	}

	@Override
	public boolean isWritten() {
		if (isDone()) {
			Object v = getValue();
			if (v instanceof Boolean)
				return (Boolean)v;
		}

		return false;
	}

	@Override
	public Throwable getException() {
		if (isDone()) {
			Object v = getValue();
			if (v instanceof Throwable)
				return (Throwable)v;
		}

		return null;
	}

	@Override
	public void setWritten() {
		setValue(Boolean.TRUE);
	}

	@Override
	public void setException(Throwable exception) {
		if (exception == null)
			throw new IllegalArgumentException("exception");

		setValue(exception);
	}

	@Override
	public WriteFuture await() throws InterruptedException {
		return (WriteFuture)super.await();
	}

	@Override
	public WriteFuture awaitUninterruptibly() {
		return (WriteFuture)super.awaitUninterruptibly();
	}

	@Override
	public WriteFuture addListener(IoFutureListener<?> listener) {
		return (WriteFuture)super.addListener(listener);
	}

	@Override
	public WriteFuture removeListener(IoFutureListener<?> listener) {
		return (WriteFuture)super.removeListener(listener);
	}
}
