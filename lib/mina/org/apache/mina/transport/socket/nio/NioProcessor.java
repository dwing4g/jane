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
package org.apache.mina.transport.socket.nio;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.Executor;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.file.FileRegion;
import org.apache.mina.core.polling.AbstractPollingIoProcessor;
import org.apache.mina.core.session.SessionState;

/**
 * A processor for incoming and outgoing data get and written on a TCP socket.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public final class NioProcessor extends AbstractPollingIoProcessor<NioSession> {
	/** The selector associated with this processor */
	private Selector selector;

	/**
	 * Creates a new instance of NioProcessor.
	 *
	 * @param executor The executor to use
	 */
	public NioProcessor(Executor executor) throws IOException {
		super(executor);

		// Open a new selector
		selector = Selector.open();
	}

	@Override
	protected void init(NioSession session) throws IOException {
		session.setSelectionKey(session.getChannel().configureBlocking(false).register(selector, SelectionKey.OP_READ, session));
	}

	@Override
	protected void destroy(NioSession session) throws IOException {
		SelectionKey key = session.getSelectionKey();
		if (key != null) {
			key.cancel();
		}

		session.getChannel().close();
	}

	@Override
	protected void doDispose() throws IOException {
		selector.close();
	}

	@Override
	protected int select(long timeout) throws IOException {
		return selector.select(timeout);
	}

	@Override
	protected boolean isSelectorEmpty() {
		return selector.keys().isEmpty();
	}

	@Override
	protected void wakeup() {
		wakeupCalled.set(true);
		selector.wakeup();
	}

	@Override
	protected Iterator<SelectionKey> allSessions() {
		return selector.keys().iterator();
	}

	@Override
	protected int allSessionsCount()
	{
		return selector.keys().size();
	}

	@Override
	protected Iterator<SelectionKey> selectedSessions() {
		return selector.selectedKeys().iterator();
	}

	@Override
	protected SessionState getState(NioSession session) {
		SelectionKey key = session.getSelectionKey();
		if (key == null) {
			// The channel is not yet regisetred to a selector
			return SessionState.OPENING;
		}

		if (key.isValid()) {
			// The session is opened
			return SessionState.OPENED;
		}

		// The session still as to be closed
		return SessionState.CLOSING;
	}

	/**
	 * In the case we are using the java select() method, this method is used to
	 * trash the buggy selector and create a new one, registering all the sockets on it.
	 */
	@Override
	protected void registerNewSelector() throws IOException {
		// Open a new selector
		Selector newSelector = Selector.open(); //NOSONAR

		// Loop on all the registered keys, and register them on the new selector
		for (SelectionKey key : selector.keys()) {
			// Don't forget to attache the session, and back!
			NioSession session = (NioSession) key.attachment();
			session.setSelectionKey(key.channel().register(newSelector, key.interestOps(), session));
		}

		// Now we can close the old selector and switch it
		selector.close();
		selector = newSelector;
	}

	@Override
	protected boolean isBrokenConnection() throws IOException {
		// A flag set to true if we find a broken session
		boolean brokenSession = false;

		// Loop on all the keys to see if one of them has a closed channel
		for (SelectionKey key : selector.keys()) {
			if (!((SocketChannel) key.channel()).isConnected()) {
				// The channel is not connected anymore. Cancel the associated key then.
				key.cancel();

				// Set the flag to true to avoid a selector switch
				brokenSession = true;
			}
		}

		return brokenSession;
	}

	@Override
	protected void setInterestedInRead(NioSession session, boolean isInterested) {
		SelectionKey key = session.getSelectionKey();
		if (key == null || !key.isValid()) {
			return;
		}

		int oldInterestOps = key.interestOps();
		int newInterestOps = oldInterestOps;

		if (isInterested) {
			newInterestOps |= SelectionKey.OP_READ;
		} else {
			newInterestOps &= ~SelectionKey.OP_READ;
		}

		if (oldInterestOps != newInterestOps) {
			key.interestOps(newInterestOps);
		}
	}

	@Override
	protected void setInterestedInWrite(NioSession session, boolean isInterested) {
		SelectionKey key = session.getSelectionKey();
		if (key == null || !key.isValid()) {
			return;
		}

		int oldInterestOps = key.interestOps();
		int newInterestOps = oldInterestOps;

		if (isInterested) {
			newInterestOps |= SelectionKey.OP_WRITE;
		} else {
			newInterestOps &= ~SelectionKey.OP_WRITE;
		}

		if (oldInterestOps != newInterestOps) {
			key.interestOps(newInterestOps);
		}
	}

	@Override
	protected int read(NioSession session, IoBuffer buf) throws IOException {
		return session.getChannel().read(buf.buf());
	}

	@Override
	protected int write(NioSession session, IoBuffer buf) throws IOException {
		return session.getChannel().write(buf.buf());
	}

	@Override
	protected int transferFile(NioSession session, FileRegion region, int length) throws IOException {
		try {
			return (int) region.getFileChannel().transferTo(region.getPosition(), length, session.getChannel());
		} catch (IOException e) {
			// Check to see if the IOException is being thrown due to
			// http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=5103988
			String message = e.getMessage();
			if (message != null && message.contains("temporarily unavailable")) {
				return 0;
			}
			throw e;
		}
	}
}
