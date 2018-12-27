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
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.file.FileRegion;
import org.apache.mina.core.future.DefaultIoFuture;
import org.apache.mina.core.service.AbstractIoService;
import org.apache.mina.core.service.IoProcessor;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.core.write.WriteRequestQueue;
import org.apache.mina.util.ExceptionMonitor;

/**
 * A processor implements of {@link IoProcessor} for incoming and outgoing data got and written on a TCP socket.
 * This class is in charge of active polling a set of {@link IoSession} and trigger events when some I/O operation is possible.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public final class NioProcessor implements IoProcessor<NioSession> {
	private static final long SELECT_TIMEOUT = 1000L;

	/** The executor to use when we need to start the inner Processor */
	private final Executor executor;

	private Selector selector;

	private final Queue<NioSession> newSessions = new ConcurrentLinkedQueue<>();
	private final Queue<NioSession> removingSessions = new ConcurrentLinkedQueue<>();
	private final Queue<NioSession> flushingSessions = new ConcurrentLinkedQueue<>();

	/** The processor thread: it handles the incoming messages */
	private final AtomicReference<Processor> processorRef = new AtomicReference<>();

	private final DefaultIoFuture disposalFuture = new DefaultIoFuture(null);
	private final AtomicBoolean wakeupCalled = new AtomicBoolean();
	private Thread processorThread;

	private volatile boolean disposing;
	private volatile boolean disposed;

	/**
	 * Create an {@link NioProcessor} with the given {@link Executor} for handling I/Os events.
	 *
	 * @param executor the {@link Executor} for handling I/O events
	 */
	public NioProcessor(Executor executor) throws IOException {
		if (executor == null) {
			throw new IllegalArgumentException("executor");
		}
		this.executor = executor;

		selector = Selector.open();
	}

	/**
	 * Interrupt the selector.select(long) call.
	 */
	private void wakeup() {
		wakeupCalled.set(true);
		selector.wakeup();
	}

	@Override
	public void add(NioSession session) {
		if (disposing) {
			throw new IllegalStateException("already disposed");
		}

		// Adds the session to the newSession queue and starts the worker
		newSessions.add(session);
		startupProcessor();
	}

	@Override
	public void remove(NioSession session) {
		scheduleRemove(session);
		startupProcessor();
	}

	private void scheduleRemove(NioSession session) {
		if (session.setScheduledForRemove()) {
			removingSessions.add(session);
		}
	}

	@Override
	public void write(NioSession session, WriteRequest writeRequest) {
		session.getWriteRequestQueue().offer(writeRequest);

		if (!session.isWriteSuspended()) {
			flush(session);
		}
	}

	public boolean isInProcessorThread() {
		return Thread.currentThread() == processorThread;
	}

	@Override
	public void flush(NioSession session) {
		if (session.isInProcessorThread() && !session.isInterestedInWrite()) {
			Processor processor = processorRef.get();
			if (processor != null) {
				processor.flushNow(session);
				return;
			}
		}

		// add the session to the queue if it's not already in the queue, then wake up the selector.select()
		if (session.setScheduledForFlush(true)) {
			flushingSessions.add(session);
			wakeup();
		}
	}

	@Override
	public void updateTrafficControl(NioSession session) {
		try {
			session.setInterestedInRead(!session.isReadSuspended());
		} catch (Exception e) {
			session.getFilterChain().fireExceptionCaught(e);
		}

		try {
			boolean isInterested = (!session.getWriteRequestQueue().isEmpty() && !session.isWriteSuspended());
			session.setInterestedInWrite(isInterested);
			if (isInterested) {
				flush(session);
			}
		} catch (Exception e) {
			session.getFilterChain().fireExceptionCaught(e);
		}
	}

	@Override
	public boolean isDisposing() {
		return disposing;
	}

	@Override
	public boolean isDisposed() {
		return disposed;
	}

	@Override
	public void dispose() {
		if (disposing) {
			return;
		}

		synchronized (this) {
			if (disposing) {
				return;
			}
			disposing = true;

			startupProcessor();
		}

		disposalFuture.awaitUninterruptibly();
		disposed = true;
	}

	/**
	 * Starts the inner Processor, asking the executor to pick a thread in its pool.
	 * The Runnable will be renamed
	 */
	private void startupProcessor() {
		Processor processor = processorRef.get();
		if (processor == null) {
			processor = new Processor();
			if (processorRef.compareAndSet(null, processor)) {
				executor.execute(processor);
			}
		}

		// Just stop the selector.select() and start it again, so that the processor can be activated immediately.
		wakeup();
	}

	/**
	 * The main loop. This is the place in charge to poll the Selector, and to
	 * process the active sessions. It's done in - handle the newly created sessions -
	 */
	private final class Processor implements Runnable {
		@Override
		public void run() {
			processorThread = Thread.currentThread();

			for (int nbTries = 10;;) {
				try {
					// This select has a timeout so that we can manage idle session when we get out of the select every second.
					// (note: this is a hack to avoid creating a dedicated thread).
					long t0 = System.currentTimeMillis();
					int selected = selector.select(SELECT_TIMEOUT);

					long delta;
					if (!wakeupCalled.getAndSet(false) && selected == 0 && (delta = System.currentTimeMillis() - t0) < 100) {
						if (Thread.interrupted()) {
							// Thread was interrupted so reset selected keys and break so we not run into a busy loop.
							// As this is most likely a bug in the handler of the user or it's client library we will also log it.
							// See https://github.com/netty/netty/issues/2426
							ExceptionMonitor.getInstance().error("selector.select() returned prematurely because Thread.interrupted()");
							break;
						}

						// Last chance: the select() may have been interrupted because we have had an closed channel.
						if (isBrokenConnection()) {
							ExceptionMonitor.getInstance().warn("broken connection");
						} else {
							// Ok, we are hit by the nasty epoll spinning.
							// Basically, there is a race condition which causes a closing file descriptor not to be
							// considered as available as a selected channel, but it stopped the select.
							// The next time we will call select(), it will exit immediately for the same reason,
							// and do so forever, consuming 100% CPU.
							// We have to destroy the selector, and register all the socket on a new one.
							if (nbTries == 0) {
								ExceptionMonitor.getInstance().warn("create a new selector. selected is 0, delta = " + delta);
								registerNewSelector();
								nbTries = 10;
							} else {
								nbTries--;
							}
						}
					} else {
						nbTries = 10;
					}

					// Manage newly created session first
					if (handleNewSessions() == 0 && selector.keys().isEmpty()) {
						// Get a chance to exit the infinite loop if there are no more sessions on this Processor
						processorRef.set(null);

						if (newSessions.isEmpty() && selector.keys().isEmpty()) {
							// newSessions.add() precedes startupProcessor
							break;
						}

						if (!processorRef.compareAndSet(null, this)) {
							// startupProcessor won race, so must exit processor
							break;
						}
					}

					// Now, if we have had some incoming or outgoing events, deal with them
					if (selected > 0) {
						process();
					}

					// Write the pending requests
					flush();

					// And manage removed sessions
					removeSessions();

					// Disconnect all sessions immediately if disposal has been requested so that we exit this loop eventually.
					if (isDisposing()) {
						for (Iterator<SelectionKey> it = selector.keys().iterator(); it.hasNext();) {
							NioSession session = (NioSession)it.next().attachment();
							scheduleRemove(session);
						}
						wakeup();
					}
				} catch (ClosedSelectorException cse) {
					// If the selector has been closed, we can exit the loop But first, dump a stack trace
					ExceptionMonitor.getInstance().exceptionCaught(cse);
					break;
				} catch (Exception e) {
					ExceptionMonitor.getInstance().exceptionCaught(e);

					try {
						Thread.sleep(1000);
					} catch (InterruptedException e1) {
						ExceptionMonitor.getInstance().exceptionCaught(e1);
					}
				}
			}

			try {
				synchronized (NioProcessor.this) {
					if (isDisposing()) {
						selector.close();
					}
				}
			} catch (Exception e) {
				ExceptionMonitor.getInstance().exceptionCaught(e);
			} finally {
				disposalFuture.setValue(true);
			}
		}

		private void process() {
			for (Iterator<SelectionKey> it = selector.selectedKeys().iterator(); it.hasNext(); it.remove()) {
				SelectionKey key = it.next();
				NioSession session = (NioSession)key.attachment();
				int ops = key.readyOps();

				// Process reads
				if ((ops & SelectionKey.OP_READ) != 0 && !session.isReadSuspended()) {
					session.read();
				}

				// Process writes
				if ((ops & SelectionKey.OP_WRITE) != 0 && !session.isWriteSuspended()) {
					// add the session to the queue, if it's not already there
					scheduleFlush(session);
				}
			}
		}
		/**
		 * Check that the select() has not exited immediately just because of a
		 * broken connection. In this case, this is a standard case, and we just have to loop.
		 *
		 * @return <tt>true</tt> if a connection has been brutally closed.
		 * @throws IOException If we got an exception
		 */
		private boolean isBrokenConnection() {
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

		/**
		 * In the case we are using the java select() method, this method is used to
		 * trash the buggy selector and create a new one, registering all the sockets on it.
		 *
		 * @throws IOException If we got an exception
		 */
		private void registerNewSelector() throws IOException {
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


		/**
		 * Loops over the new sessions blocking queue and returns the number of sessions which are effectively created
		 *
		 * @return The number of new sessions
		 */
		private int handleNewSessions() {
			int addedSessions = 0;
			NioSession session;

			while ((session = newSessions.poll()) != null) {
				if (addNow(session)) {
					// A new session has been created
					addedSessions++;
				}
			}

			return addedSessions;
		}

		/**
		 * Process a new session: - initialize it - create its chain - fire the CREATED listeners if any
		 *
		 * @param session The session to create
		 * @return <tt>true</tt> if the session has been registered
		 */
		private boolean addNow(NioSession session) {
			boolean registered = false;

			try {
				session.setSelectionKey(session.getChannel().configureBlocking(false).register(selector, SelectionKey.OP_READ, session));
				registered = true;

				// Build the filter chain of this session.
				session.getService().getFilterChainBuilder().buildFilterChain(session.getFilterChain());

				// DefaultIoFilterChain.CONNECT_FUTURE is cleared inside here in AbstractIoFilterChain.fireSessionOpened().
				// Propagate the SESSION_CREATED event up to the chain
				((AbstractIoService) session.getService()).fireSessionCreated(session);
			} catch (Exception e) {
				ExceptionMonitor.getInstance().exceptionCaught(e);

				try {
					session.destroy();
				} catch (Exception e1) {
					ExceptionMonitor.getInstance().exceptionCaught(e1);
				} finally {
					registered = false;
					session.setScheduledForRemove();
				}
			}

			return registered;
		}

		private void removeSessions() {
			NioSession session;
			while ((session = removingSessions.poll()) != null) {
				// Now deal with the removal accordingly to the session's state
				switch (session.getState()) {
					case OPENING: // Remove session from the newSessions queue and remove it
						newSessions.remove(session);
						//$FALL-THROUGH$
					case OPENED: // Try to remove this session
						session.removeNow(null);
						//$FALL-THROUGH$
					case CLOSING: // Skip if channel is already closed In any case, remove the session from the queue
						break;
				}
			}
		}

		/**
		 * Write all the pending messages
		 */
		private void flush() {
			NioSession session;
			while ((session = flushingSessions.poll()) != null) { // the same one with firstSession
				// Reset the Schedule for flush flag for this session, as we are flushing it now
				session.unscheduledForFlush();

				switch (session.getState()) {
					case OPENING:
						// Retry later if session is not yet fully initialized.
						// (In case that Session.write() is called before addSession() is processed)
						scheduleFlush(session);
						return;
					case OPENED:
						try {
							flushNow(session);
						} catch (Exception e) {
							session.closeNow();
							session.getFilterChain().fireExceptionCaught(e);
						}
						//$FALL-THROUGH$
					case CLOSING: // Skip if the channel is already closed.
						break;
				}
			}
		}

		private void scheduleFlush(NioSession session) {
			// add the session to the queue if it's not already in the queue
			if (session.setScheduledForFlush(true)) {
				flushingSessions.add(session);
			}
		}

		void flushNow(NioSession session) {
			if (!session.isConnected()) {
				scheduleRemove(session);
				return;
			}

			final WriteRequestQueue writeRequestQueue = session.getWriteRequestQueue();

			// Set limitation for the number of written bytes for read-write fairness.
			// I used maxReadBufferSize * 3 / 2, which yields best performance in my experience while not breaking fairness much.
			final int maxWrittenBytes = session.getConfig().getMaxReadBufferSize() + (session.getConfig().getMaxReadBufferSize() >>> 1);
			int writtenBytes = 0;
			WriteRequest req = null;

			try {
				for(;;) {
					req = session.getCurrentWriteRequest(); // Check for pending writes.
					if (req == null) {
						req = writeRequestQueue.poll();
						if (req == null) {
							session.setInterestedInWrite(false);
							return;
						}

						session.setCurrentWriteRequest(req);
					}

					int localWrittenBytes = 0;
					Object message = req.writeRequestMessage();

					if (message instanceof IoBuffer) {
						IoBuffer buf = (IoBuffer) message;
						if (buf.hasRemaining()) {
							try {
								localWrittenBytes = session.getChannel().write(buf.buf());
							} catch (IOException ioe) {
								session.setCurrentWriteRequest(null);
								req.writeRequestFuture().setException(ioe);
								buf.free();
								// we have had an issue while trying to send data to the peer, let's close the session
								session.closeNow();
								session.removeNow(ioe);
								return;
							}

							if (buf.hasRemaining()) { // the buffer isn't empty, we re-interest it in writing
								session.setInterestedInWrite(true);
								return;
							}
						}
					} else if (message instanceof FileRegion) {
						FileRegion region = (FileRegion) message;
						int length = (int) Math.min(region.getRemainingBytes(), (long)maxWrittenBytes - writtenBytes);
						if (length > 0) {
							localWrittenBytes = session.transferFile(region, length);
							region.update(localWrittenBytes);
						}

						// Fix for Java bug on Linux
						// http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=5103988
						// If there's still data to be written in the FileRegion,
						// return 0 indicating that we need to pause until writing may resume.
						if (region.getRemainingBytes() > 0) {
							session.setInterestedInWrite(true);
							return;
						}
					} else {
						throw new IllegalStateException("unknown message type for writting: " + message.getClass().getName() + ": " + message);
					}

					session.setCurrentWriteRequest(null);
					session.getFilterChain().fireMessageSent(req);

					if (message instanceof IoBuffer) {
						((IoBuffer) message).free();
					}

					writtenBytes += localWrittenBytes;
					if (writtenBytes >= maxWrittenBytes) {
						// Wrote too much
						scheduleFlush(session);
						session.setInterestedInWrite(false);
						return;
					}
				}
			} catch (Exception e) {
				try {
					session.setInterestedInWrite(false);
				} catch(Exception ex) {
					session.getFilterChain().fireExceptionCaught(ex);
				}
				if (req != null) {
					req.writeRequestFuture().setException(e);
				}

				session.getFilterChain().fireExceptionCaught(e);
			}
		}
	}
}
