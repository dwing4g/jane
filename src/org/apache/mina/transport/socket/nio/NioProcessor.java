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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
 */
public final class NioProcessor implements IoProcessor<NioSession> {
	private final Executor executor;
	private Selector selector;
	private final AtomicReference<Processor> processorRef = new AtomicReference<>();

	private final Queue<NioSession> creatingSessions = new ConcurrentLinkedQueue<>();
	private final Queue<NioSession> flushingSessions = new ConcurrentLinkedQueue<>();
	private final Queue<NioSession> removingSessions = new ConcurrentLinkedQueue<>();

	private final DefaultIoFuture disposalFuture = new DefaultIoFuture(null);
	private final AtomicBoolean wakeupCalled = new AtomicBoolean();
	private Thread processorThread;

	private volatile boolean disposing;

	public NioProcessor(Executor executor) throws IOException {
		if (executor == null)
			throw new IllegalArgumentException("executor");
		this.executor = executor;
		selector = Selector.open();
	}

	public void wakeup() {
		if (wakeupCalled.compareAndSet(false, true))
			selector.wakeup();
	}

	@Override
	public void add(NioSession session) {
		synchronized (creatingSessions) {
			if (disposing)
				throw new IllegalStateException("disposed processor");
			creatingSessions.add(session);
		}
		startupProcessor();
	}

	@Override
	public void remove(NioSession session) {
		if (scheduleRemove(session))
			startupProcessor();
	}

	private boolean scheduleRemove(NioSession session) {
		if (!session.setScheduledForRemove())
			return false;
		removingSessions.add(session);
		return true;
	}

	@Override
	public void write(NioSession session, WriteRequest writeRequest) {
		session.getWriteRequestQueue().offer(writeRequest);
		flush(session);
	}

	public boolean isInProcessorThread() {
		return Thread.currentThread() == processorThread;
	}

	@Override
	public void flush(NioSession session) {
		if (isInProcessorThread() && !session.isInterestedInWrite()) {
			Processor processor = processorRef.get();
			if (processor != null) {
				processor.flushNow(session);
				return;
			}
		}

		if (session.setScheduledForFlush()) {
			flushingSessions.add(session);
			wakeup();
		}
	}

	@Override
	public boolean isDisposing() {
		return disposing;
	}

	@Override
	public boolean isDisposed() {
		return disposalFuture.isDone();
	}

	@Override
	public void dispose() {
		synchronized (creatingSessions) {
			if (disposing)
				return;
			disposing = true;
			startupProcessor();
			disposalFuture.awaitUninterruptibly();
		}
	}

	private void startupProcessor() {
		Processor processor = processorRef.get();
		if (processor == null && processorRef.compareAndSet(null, processor = new Processor()))
			executor.execute(processor);
		else
			wakeup();
	}

	private final class Processor implements Runnable, Consumer<SelectionKey> {
		@Override
		public void run() {
			processorThread = Thread.currentThread();
			for (int nbTries = 10;;) {
				if (disposing) {
					if (!isDisposed()) {
						try {
							for (SelectionKey key : selector.keys())
								scheduleRemove((NioSession)key.attachment());
							removeSessions();
							selector.close();
						} catch (Exception e) {
							ExceptionMonitor.getInstance().exceptionCaught(e);
						} finally {
							disposalFuture.setValue(true);
						}
					}
					return;
				}
				try {
					createSessions();
					if (selector.keys().isEmpty()) {
						processorRef.set(null);
						if (creatingSessions.isEmpty() || !processorRef.compareAndSet(null, this))
							return;
					}

					int selected = selector.select(this);
					if (wakeupCalled.compareAndSet(true, false) || selected > 0)
						nbTries = 10;
					else if ((nbTries = fixSelector(nbTries)) < 0)
						return;

					flushSessions();
					removeSessions();
				} catch (Exception e) {
					ExceptionMonitor.getInstance().exceptionCaught(e);
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e1) {
						processorRef.compareAndSet(this, null);
						ExceptionMonitor.getInstance().exceptionCaught(e1);
					}
				}
			}
		}

		@Override
		public void accept(SelectionKey key) {
			NioSession session = (NioSession)key.attachment();
			int ops = key.readyOps();
			if ((ops & SelectionKey.OP_READ) != 0)
				session.read();
			if ((ops & SelectionKey.OP_WRITE) != 0)
				scheduleFlush(session);
		}

		private void createSessions() {
			NioSession session;
			while ((session = creatingSessions.poll()) != null) {
				try {
					session.setSelectionKey(session.getChannel().configureBlocking(false).register(selector, SelectionKey.OP_READ, session));
					AbstractIoService service = session.getService();
					service.getFilterChainBuilder().buildFilterChain(session.getFilterChain());
					service.fireSessionCreated(session);
				} catch (Exception e) {
					ExceptionMonitor.getInstance().exceptionCaught(e);
					try {
						session.destroy();
					} catch (Exception e1) {
						ExceptionMonitor.getInstance().exceptionCaught(e1);
					} finally {
						session.setScheduledForRemove();
					}
				}
			}
		}

		private void scheduleFlush(NioSession session) {
			if (session.setScheduledForFlush())
				flushingSessions.add(session);
		}

		private void flushSessions() {
			NioSession session;
			while ((session = flushingSessions.poll()) != null) {
				// Reset the Schedule for flush flag for this session, as we are flushing it now.
				// This allows another thread to enqueue data to be written without corrupting the selector interest state.
				session.unscheduledForFlush();
				if (session.isActive())
					flushNow(session);
			}
		}

		private void removeSessions() {
			NioSession session;
			while ((session = removingSessions.poll()) != null) {
				if (session.isActive())
					session.removeNow(null);
				else if (session.isOpening()) {
					creatingSessions.remove(session);
					session.removeNow(null);
				}
			}
		}

		void flushNow(NioSession session) {
			if (session.isClosing())
				return;
			try {
				WriteRequestQueue writeQueue = session.getWriteRequestQueue();
				for (WriteRequest req; (req = writeQueue.peek()) != null; writeQueue.poll()) {
					Object message = req.writeRequestMessage();
					if (message instanceof IoBuffer) {
						IoBuffer buf = (IoBuffer)message;
						if (buf.hasRemaining()) {
							session.getChannel().write(buf.buf());
							if (buf.hasRemaining()) {
								session.setInterestedInWrite(true);
								return;
							}
						}
						req.writeRequestFuture().setWritten();
						buf.free();
					} else if (message instanceof FileRegion) {
						FileRegion region = (FileRegion)message;
						long len = region.getRemainingBytes();
						if (len > 0) {
							region.update(region.getFileChannel().transferTo(region.getPosition(), len, session.getChannel()));
    						if (region.getRemainingBytes() > 0) {
    							session.setInterestedInWrite(true);
    							return;
    						}
						}
						req.writeRequestFuture().setWritten();
					} else if (req == NioSession.CLOSE_REQUEST) {
						session.closeNow();
						break;
					} else if (req == NioSession.SHUTDOWN_REQUEST) {
						session.getChannel().shutdownOutput();
						break;
					} else
						throw new IllegalStateException("unknown message type for writting: " + message.getClass().getName() + ": " + message);
				}
				session.setInterestedInWrite(false);
			} catch (Exception e) { // TCP RST
				session.closeNow();
				session.removeNow(e);
			}
		}

		private int fixSelector(int nbTries) throws IOException {
			if (Thread.interrupted()) {
				// Thread was interrupted so reset selected keys and break so we not run into a busy loop.
				// As this is most likely a bug in the handler of the user or it's client library we will also log it.
				// See https://github.com/netty/netty/issues/2426
				ExceptionMonitor.getInstance().error("selector.select() returned prematurely because Thread.interrupted()");
				processorRef.compareAndSet(this, null);
				return -1;
			}

			// Last chance: the select() may have been interrupted because we have had an closed channel.
			// Check that the select() has not exited immediately just because of a broken connection.
			// In this case, this is a standard case, and we just have to loop.
			boolean brokenSession = false;
			for (SelectionKey key : selector.keys()) {
				if (!((SocketChannel)key.channel()).isConnected()) {
					key.cancel();
					brokenSession = true;
				}
			}
			if (brokenSession)
				ExceptionMonitor.getInstance().warn("fixed broken connection");
			else {
				// Ok, we are hit by the nasty epoll spinning.
				// Basically, there is a race condition which causes a closing file descriptor not to be
				// considered as available as a selected channel, but it stopped the select.
				// The next time we will call select(), it will exit immediately for the same reason,
				// and do so forever, consuming 100% CPU.
				// We have to destroy the selector, and register all the socket on a new one.
				if (nbTries <= 0) {
					ExceptionMonitor.getInstance().warn("create a new selector");
					// In the case we are using the java select() method,
					// this method is used to trash the buggy selector and create a new one, registering all the sockets on it.
					Selector newSelector = Selector.open();

					// Loop on all the registered keys, and register them on the new selector
					for (SelectionKey key : selector.keys()) {
						// Don't forget to attache the session, and back!
						NioSession session = (NioSession)key.attachment();
						session.setSelectionKey(key.channel().register(newSelector, key.interestOps(), session));
					}

					// Now we can close the old selector and switch it
					selector.close();
					selector = newSelector;
					nbTries = 10;
				} else
					nbTries--;
			}
			return nbTries;
		}
	}
}
