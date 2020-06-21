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

/**
 * A default implementation of {@link CloseFuture}.
 */
public final class DefaultCloseFuture extends DefaultIoFuture implements CloseFuture {
	/**
	 * @param session The associated session
	 */
	public DefaultCloseFuture(IoSession session) {
		super(session);
	}

	@Override
	public void setClosed() {
		setValue(Boolean.TRUE);
	}

	@Override
	public CloseFuture await() throws InterruptedException {
		return (CloseFuture)super.await();
	}

	@Override
	public CloseFuture awaitUninterruptibly() {
		return (CloseFuture)super.awaitUninterruptibly();
	}

	@Override
	public CloseFuture addListener(IoFutureListener<?> listener) {
		return (CloseFuture)super.addListener(listener);
	}

	@Override
	public CloseFuture removeListener(IoFutureListener<?> listener) {
		return (CloseFuture)super.removeListener(listener);
	}
}
