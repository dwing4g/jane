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
package org.apache.mina.core.polling;

import java.io.IOException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
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
import org.apache.mina.core.session.AbstractIoSession;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.session.SessionState;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.core.write.WriteRequestQueue;
import org.apache.mina.core.write.WriteToClosedSessionException;
import org.apache.mina.util.ExceptionMonitor;

/**
 * An abstract implementation of {@link IoProcessor} which helps transport developers to write an {@link IoProcessor} easily.
 * This class is in charge of active polling a set of {@link IoSession} and trigger events when some I/O operation is possible.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 *
 * @param <S> the type of the {@link IoSession} this processor can handle
 */
public abstract class AbstractPollingIoProcessor<S extends AbstractIoSession> implements IoProcessor<S> {
	/** A timeout used for the select */
	private static final long SELECT_TIMEOUT = 1000L;

	/** The executor to use when we need to start the inner Processor */
	private final Executor executor;

	/** A Session queue containing the newly created sessions */
	private final Queue<S> newSessions = new ConcurrentLinkedQueue<>();

	/** A queue used to store the sessions to be removed */
	private final Queue<S> removingSessions = new ConcurrentLinkedQueue<>();

	/** A queue used to store the sessions to be flushed */
	private final Queue<S> flushingSessions = new ConcurrentLinkedQueue<>();

	/** The processor thread: it handles the incoming messages */
	private final AtomicReference<Processor> processorRef = new AtomicReference<>();

	private final Object disposalLock = new Object();

	private final DefaultIoFuture disposalFuture = new DefaultIoFuture(null);

	protected final AtomicBoolean wakeupCalled = new AtomicBoolean();

	private Thread processorThread;

	private volatile boolean disposing;
	private volatile boolean disposed;

	/**
	 * Create an {@link AbstractPollingIoProcessor} with the given {@link Executor} for handling I/Os events.
	 *
	 * @param executor the {@link Executor} for handling I/O events
	 */
	protected AbstractPollingIoProcessor(Executor executor) {
		if (executor == null) {
			throw new IllegalArgumentException("executor");
		}
		this.executor = executor;
	}

	/**
	 * Initialize the polling of a session. Add it to the polling process.
	 *
	 * @param session the {@link IoSession} to add to the polling
	 * @throws IOException any exception thrown by the underlying system calls
	 */
	protected abstract void init(S session) throws IOException;

	/**
	 * Destroy the underlying client socket handle
	 *
	 * @param session the {@link IoSession}
	 * @throws IOException any exception thrown by the underlying system calls
	 */
	protected abstract void destroy(S session) throws IOException;

	/**
	 * Dispose the resources used by this {@link IoProcessor} for polling the
	 * client connections. The implementing class doDispose method will be called.
	 *
	 * @throws IOException if some low level IO error occurs
	 */
	protected abstract void doDispose() throws IOException;

	/**
	 * In the case we are using the java select() method, this method is used to
	 * trash the buggy selector and create a new one, registring all the sockets on it.
	 *
	 * @throws IOException If we got an exception
	 */
	protected abstract void registerNewSelector() throws IOException;

	/**
	 * Check that the select() has not exited immediately just because of a
	 * broken connection. In this case, this is a standard case, and we just have to loop.
	 *
	 * @return <tt>true</tt> if a connection has been brutally closed.
	 * @throws IOException If we got an exception
	 */
	protected abstract boolean isBrokenConnection() throws IOException;

	/**
	 * poll those sessions for the given timeout
	 *
	 * @param timeout milliseconds before the call timeout if no event appear
	 * @return The number of session ready for read or for write
	 * @throws IOException if some low level IO error occurs
	 */
	protected abstract int select(long timeout) throws IOException;

	/**
	 * Say if the list of {@link IoSession} polled by this {@link IoProcessor} is empty
	 *
	 * @return <tt>true</tt> if at least a session is managed by this {@link IoProcessor}
	 */
	protected abstract boolean isSelectorEmpty();

	/**
	 * Interrupt the {@link #select(long)} call.
	 */
	protected abstract void wakeup();

	/**
	 * Get an {@link Iterator} for the list of {@link IoSession} polled by this {@link IoProcessor}
	 *
	 * @return {@link Iterator} of {@link IoSession}
	 */
	protected abstract Iterator<SelectionKey> allSessions();

	/**
	 * Get an {@link Iterator} for the list of {@link IoSession} found selected by the last call of {@link #select(long)}
	 *
	 * @return {@link Iterator} of {@link IoSession} read for I/Os operation
	 */
	protected abstract Iterator<SelectionKey> selectedSessions();

	/**
	 * Get the state of a session (One of OPENING, OPEN, CLOSING)
	 *
	 * @param session the {@link IoSession} to inspect
	 * @return the state of the session
	 */
	protected abstract SessionState getState(S session);

	/**
	 * Set the session to be informed when a read event should be processed
	 *
	 * @param session the session for which we want to be interested in read events
	 * @param isInterested <tt>true</tt> for registering, <tt>false</tt> for removing
	 * @throws Exception If there was a problem while registering the session
	 */
	protected abstract void setInterestedInRead(S session, boolean isInterested);

	/**
	 * Set the session to be informed when a write event should be processed
	 *
	 * @param session the session for which we want to be interested in write events
	 * @param isInterested <tt>true</tt> for registering, <tt>false</tt> for removing
	 * @throws Exception If there was a problem while registering the session
	 */
	protected abstract void setInterestedInWrite(S session, boolean isInterested);

	/**
	 * Reads a sequence of bytes from a {@link IoSession} into the given {@link IoBuffer}.
	 * Is called when the session was found ready for reading.
	 *
	 * @param session the session to read
	 * @param buf the buffer to fill
	 * @return the number of bytes read
	 * @throws IOException any exception thrown by the underlying system calls
	 */
	protected abstract int read(S session, IoBuffer buf) throws IOException;

	/**
	 * Write a sequence of bytes to a {@link IoSession},
	 * means to be called when a session was found ready for writing.
	 *
	 * @param session the session to write
	 * @param buf the buffer to write
	 * @return the number of byte written
	 * @throws IOException any exception thrown by the underlying system calls
	 */
	protected abstract int write(S session, IoBuffer buf) throws IOException;

	/**
	 * Write a part of a file to a {@link IoSession}, if the underlying API isn't supporting
	 * system calls like sendfile(), you can throw a {@link UnsupportedOperationException}
	 * so the file will be send using usual {@link #write(AbstractIoSession, IoBuffer, int)} call.
	 *
	 * @param session the session to write
	 * @param region the file region to write
	 * @param length the length of the portion to send
	 * @return the number of written bytes
	 * @throws IOException any exception thrown by the underlying system calls
	 */
	protected abstract int transferFile(S session, FileRegion region, int length) throws IOException;

	@Override
	public final void add(S session) {
		if (disposed || disposing) {
			throw new IllegalStateException("Already disposed.");
		}

		// Adds the session to the newSession queue and starts the worker
		newSessions.add(session);
		startupProcessor();
	}

	@Override
	public final void remove(S session) {
		scheduleRemove(session);
		startupProcessor();
	}

	private void scheduleRemove(S session) {
		if (!removingSessions.contains(session)) {
			removingSessions.add(session);
		}
	}

	@Override
	public void write(S session, WriteRequest writeRequest) {
		session.getWriteRequestQueue().offer(writeRequest);

		if (!session.isWriteSuspended()) {
			flush(session);
		}
	}

	public final boolean isInProcessorThread() {
		return Thread.currentThread() == processorThread;
	}

	@Override
	public final void flush(S session) {
		if (session.isInProcessorThread() && !session.isInterestedInWrite()) {
			processorRef.get().flushNow(session);
			return;
		}

		// add the session to the queue if it's not already in the queue, then wake up the select()
		if (session.setScheduledForFlush(true)) {
			flushingSessions.add(session);
			wakeup();
		}
	}

	@Override
	public void updateTrafficControl(S session) {
		try {
			setInterestedInRead(session, !session.isReadSuspended());
		} catch (Exception e) {
			session.getFilterChain().fireExceptionCaught(e);
		}

		try {
			boolean isInterested = !session.getWriteRequestQueue().isEmpty() && !session.isWriteSuspended();
			setInterestedInWrite(session, isInterested);
			if (isInterested) {
				flush(session);
			}
		} catch (Exception e) {
			session.getFilterChain().fireExceptionCaught(e);
		}
	}

	@Override
	public final boolean isDisposing() {
		return disposing;
	}

	@Override
	public final boolean isDisposed() {
		return disposed;
	}

	@Override
	public final void dispose() {
		if (disposed || disposing) {
			return;
		}

		synchronized (disposalLock) {
			disposing = true;
			startupProcessor();
		}

		disposalFuture.awaitUninterruptibly();
		disposed = true;
	}

	private void read(S session) {
		try {
			int readBufferSize = session.getConfig().getReadBufferSize();
			IoBuffer buf = IoBuffer.allocate(readBufferSize);
			int readBytes = read(session, buf);

			if (readBytes > 0) {
				if ((readBytes << 1) < readBufferSize) {
					session.decreaseReadBufferSize();
				} else if (readBytes >= readBufferSize) {
					session.increaseReadBufferSize();
				}
				session.getFilterChain().fireMessageReceived(buf.flip());
			} else {
				// release temporary buffer when read nothing
				buf.free();
				if (readBytes < 0) {
					session.getFilterChain().fireInputClosed();
				}
			}
		} catch (IOException e) {
			session.closeNow();
			session.getFilterChain().fireExceptionCaught(e);
		} catch (Exception e) {
			session.getFilterChain().fireExceptionCaught(e);
		}
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

		// Just stop the select() and start it again, so that the processor can be activated immediately.
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
			int nSessions = 0;
			int nbTries = 10;

			for (;;) {
				try {
					// This select has a timeout so that we can manage idle session when we get out of the select every second.
					// (note: this is a hack to avoid creating a dedicated thread).
					long t0 = System.currentTimeMillis();
					int selected = select(SELECT_TIMEOUT);

					long delta;
					if (!wakeupCalled.getAndSet(false) && selected == 0 && (delta = System.currentTimeMillis() - t0) < 100) {
						if (Thread.interrupted()) {
							// Thread was interrupted so reset selected keys and break so we not run into a busy loop.
							// As this is most likely a bug in the handler of the user or it's client library we will also log it.
							// See https://github.com/netty/netty/issues/2426
							ExceptionMonitor.getInstance().error("Selector.select() returned prematurely because Thread.interrupted()");
							break;
						}

						// Last chance: the select() may have been interrupted because we have had an closed channel.
						if (isBrokenConnection()) {
							ExceptionMonitor.getInstance().warn("Broken connection");
						} else {
							// Ok, we are hit by the nasty epoll spinning.
							// Basically, there is a race condition which causes a closing file descriptor not to be
							// considered as available as a selected channel, but it stopped the select.
							// The next time we will call select(), it will exit immediately for the same reason,
							// and do so forever, consuming 100% CPU.
							// We have to destroy the selector, and register all the socket on a new one.
							if (nbTries == 0) {
								ExceptionMonitor.getInstance().warn("Create a new selector. Selected is 0, delta = " + delta);
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
					nSessions += handleNewSessions();

					// Now, if we have had some incoming or outgoing events, deal with them
					if (selected > 0) {
						process();
					}

					// Write the pending requests
					flush();

					// And manage removed sessions
					nSessions -= removeSessions();

					// Get a chance to exit the infinite loop if there are no more sessions on this Processor
					if (nSessions == 0) {
						processorRef.set(null);

						if (newSessions.isEmpty() && isSelectorEmpty()) {
							// newSessions.add() precedes startupProcessor
							break;
						}

						if (!processorRef.compareAndSet(null, this)) {
							// startupProcessor won race, so must exit processor
							break;
						}
					}

					// Disconnect all sessions immediately if disposal has been requested so that we exit this loop eventually.
					if (isDisposing()) {
						boolean hasKeys = false;

						for (Iterator<SelectionKey> i = allSessions(); i.hasNext();) {
							@SuppressWarnings("unchecked")
							S session = (S)i.next().attachment();
							scheduleRemove(session);

							if (session.isActive()) {
								hasKeys = true;
							}
						}

						if (hasKeys) {
							wakeup();
						}
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
				synchronized (disposalLock) {
					if (disposing) {
						doDispose();
					}
				}
			} catch (Exception e) {
				ExceptionMonitor.getInstance().exceptionCaught(e);
			} finally {
				disposalFuture.setValue(true);
			}
		}

		/**
		 * Loops over the new sessions blocking queue and returns the number of sessions which are effectively created
		 *
		 * @return The number of new sessions
		 */
		private int handleNewSessions() {
			int addedSessions = 0;

			for (S session = newSessions.poll(); session != null; session = newSessions.poll()) {
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
		private boolean addNow(S session) {
			boolean registered = false;

			try {
				init(session);
				registered = true;

				// Build the filter chain of this session.
				session.getService().getFilterChainBuilder().buildFilterChain(session.getFilterChain());

				// DefaultIoFilterChain.CONNECT_FUTURE is cleared inside here in AbstractIoFilterChain.fireSessionOpened().
				// Propagate the SESSION_CREATED event up to the chain
				((AbstractIoService) session.getService()).fireSessionCreated(session);
			} catch (Exception e) {
				ExceptionMonitor.getInstance().exceptionCaught(e);

				try {
					destroy(session);
				} catch (Exception e1) {
					ExceptionMonitor.getInstance().exceptionCaught(e1);
				} finally {
					registered = false;
				}
			}

			return registered;
		}

		private int removeSessions() {
			int removedSessions = 0;

			for (S session = removingSessions.poll(); session != null; session = removingSessions.poll()) {
				SessionState state = getState(session);

				// Now deal with the removal accordingly to the session's state
				switch (state) {
					case OPENED:
						// Try to remove this session
						if (removeNow(session, null)) {
							removedSessions++;
						}

						break;

					case CLOSING:
						// Skip if channel is already closed In any case, remove the session from the queue
						removedSessions++;
						break;

					case OPENING:
						// Remove session from the newSessions queue and remove it
						newSessions.remove(session);

						if (removeNow(session, null)) {
							removedSessions++;
						}

						break;

					default:
						throw new IllegalStateException(String.valueOf(state));
				}
			}

			return removedSessions;
		}

		/**
		 * Write all the pending messages
		 */
		private void flush() {
			for(;;) {
				S session = flushingSessions.poll(); // the same one with firstSession
				if (session == null) {
					break;
				}

				// Reset the Schedule for flush flag for this session, as we are flushing it now
				session.unscheduledForFlush();

				SessionState state = getState(session);
				switch (state) {
					case OPENED:
						try {
							flushNow(session);
						} catch (Exception e) {
							session.closeNow();
							session.getFilterChain().fireExceptionCaught(e);
						}

						break;

					case CLOSING:
						// Skip if the channel is already closed.
						break;

					case OPENING:
						// Retry later if session is not yet fully initialized.
						// (In case that Session.write() is called before addSession() is processed)
						scheduleFlush(session);
						return;

					default:
						throw new IllegalStateException(String.valueOf(state));
				}
			}
		}

		private void flushNow(S session) {
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
							setInterestedInWrite(session, false);
							return;
						}

						session.setCurrentWriteRequest(req);
					}

					int localWrittenBytes = 0;
					Object message = req.getMessage();

					if (message instanceof IoBuffer) {
						IoBuffer buf = (IoBuffer) message;
						if (buf.hasRemaining()) {
							try {
								localWrittenBytes = write(session, buf);
							} catch (IOException ioe) {
								session.setCurrentWriteRequest(null);
								req.getFuture().setException(ioe);
								buf.free();
								// we have had an issue while trying to send data to the peer, let's close the session
								session.closeNow();
								removeNow(session, ioe);
								return;
							}

							if (buf.hasRemaining()) { // the buffer isn't empty, we re-interest it in writing
								setInterestedInWrite(session, true);
								return;
							}
						}
					} else if (message instanceof FileRegion) {
						FileRegion region = (FileRegion) message;
						int length = (int) Math.min(region.getRemainingBytes(), maxWrittenBytes - writtenBytes);
						if (length > 0) {
							localWrittenBytes = transferFile(session, region, length);
							region.update(localWrittenBytes);
						}

						// Fix for Java bug on Linux
						// http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=5103988
						// If there's still data to be written in the FileRegion,
						// return 0 indicating that we need to pause until writing may resume.
						if (region.getRemainingBytes() > 0) {
							setInterestedInWrite(session, true);
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
						setInterestedInWrite(session, false);
						return;
					}
				}
			} catch (Exception e) {
				try {
					setInterestedInWrite(session, false);
				} catch(Exception ex) {
					session.getFilterChain().fireExceptionCaught(ex);
				}
				if (req != null) {
					req.getFuture().setException(e);
				}

				session.getFilterChain().fireExceptionCaught(e);
			}
		}

		private void scheduleFlush(S session) {
			// add the session to the queue if it's not already in the queue
			if (session.setScheduledForFlush(true)) {
				flushingSessions.add(session);
			}
		}

		private boolean removeNow(S session, IOException ioe) {
			clearWriteRequestQueue(session, ioe);

			try {
				destroy(session);
				return true;
			} catch (Exception e) {
				session.getFilterChain().fireExceptionCaught(e);
			} finally {
				try {
					clearWriteRequestQueue(session, null);
					((AbstractIoService) session.getService()).fireSessionDestroyed(session);
				} catch (Exception e) {
					// The session was either destroyed or not at this point.
					// We do not want any exception thrown from this "cleanup" code
					// to change the return value by bubbling up.
					session.getFilterChain().fireExceptionCaught(e);
				}
			}

			return false;
		}

		private void clearWriteRequestQueue(S session, IOException ioe) {
			WriteRequestQueue writeRequestQueue = session.getWriteRequestQueue();
			if (writeRequestQueue == null) { // currentWriteRequest must be null
				return;
			}

			WriteRequest req = session.getCurrentWriteRequest();
			if (req == null) {
				req = writeRequestQueue.poll();
				if (req == null) {
					return;
				}
			} else {
				session.setCurrentWriteRequest(null);
			}

			// Create an exception and notify.
			WriteToClosedSessionException cause = (ioe != null ?
					new WriteToClosedSessionException(ioe) : new WriteToClosedSessionException());

			do {
				req.getFuture().setException(cause);

				Object message = req.getMessage();
				if (message instanceof IoBuffer) {
					((IoBuffer) message).free();
				}
			} while ((req = writeRequestQueue.poll()) != null);

			session.getFilterChain().fireExceptionCaught(cause);
		}

		private void process() throws Exception {
			for (Iterator<SelectionKey> i = selectedSessions(); i.hasNext();) {
				SelectionKey key = i.next();
				@SuppressWarnings("unchecked")
				S session = (S)key.attachment();
				int ops = key.readyOps();

				// Process Reads
				if ((ops & SelectionKey.OP_READ) != 0 && !session.isReadSuspended()) {
					read(session);
				}

				// Process writes
				if ((ops & SelectionKey.OP_WRITE) != 0 && !session.isWriteSuspended() && session.setScheduledForFlush(true)) {
					// add the session to the queue, if it's not already there
					flushingSessions.add(session);
				}

				i.remove();
			}
		}
	}
}
