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
package org.apache.mina.core.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.mina.core.future.IoFuture;

/**
 * A base implementation of {@link IoAcceptor}.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public abstract class AbstractIoAcceptor extends AbstractIoService implements IoAcceptor {
	private final Set<InetSocketAddress> boundAddresses = new HashSet<>();

	/**
	 * The lock object which is acquired while bind or unbind operation is performed.
	 * Acquire this lock in your property setters which shouldn't be changed while
	 * the service is bound.
	 */
	protected final Object bindLock = new Object();

	private boolean disconnectOnUnbind = true;

	@Override
	public InetSocketAddress getLocalAddress() {
		ArrayList<InetSocketAddress> localAddresses = getLocalAddresses();
		return localAddresses.isEmpty() ? null : localAddresses.get(0);
	}

	@Override
	public final ArrayList<InetSocketAddress> getLocalAddresses() {
		ArrayList<InetSocketAddress> localAddresses = new ArrayList<>();

		synchronized (boundAddresses) {
			localAddresses.addAll(boundAddresses);
		}

		return localAddresses;
	}

	@Override
	public final boolean isCloseOnDeactivation() {
		return disconnectOnUnbind;
	}

	@Override
	public final void setCloseOnDeactivation(boolean disconnectClientsOnUnbind) {
		disconnectOnUnbind = disconnectClientsOnUnbind;
	}

	@Override
	public final void bind(SocketAddress localAddress) throws IOException {
		if (localAddress == null) {
			throw new IllegalArgumentException("localAddress");
		}

		List<SocketAddress> localAddresses = new ArrayList<>(1);
		localAddresses.add(localAddress);
		bind(localAddresses);
	}

	@Override
	public final void bind(List<? extends SocketAddress> localAddresses) throws IOException {
		if (isDisposing()) {
			throw new IllegalStateException("The Accpetor disposed is being disposed.");
		}

		if (localAddresses == null) {
			throw new IllegalArgumentException("localAddresses");
		}

		boolean activate = false;
		synchronized (bindLock) {
			synchronized (boundAddresses) {
				if (boundAddresses.isEmpty()) {
					activate = true;
				}
			}

			if (getHandler() == null) {
				throw new IllegalStateException("handler is not set.");
			}

			try {
				Set<InetSocketAddress> addresses = bindInternal(localAddresses);

				synchronized (boundAddresses) {
					boundAddresses.addAll(addresses);
				}
			} catch (IOException | RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException("Failed to bind to: " + getLocalAddresses(), e);
			}
		}

		if (activate) {
			fireServiceActivated();
		}
	}

	@Override
	public final void unbind() {
		unbind(getLocalAddresses());
	}

	@Override
	public final void unbind(SocketAddress localAddress) {
		if (localAddress == null) {
			throw new IllegalArgumentException("localAddress");
		}

		List<SocketAddress> localAddresses = new ArrayList<>(1);
		localAddresses.add(localAddress);
		unbind(localAddresses);
	}

	@Override
	public final void unbind(Iterable<? extends SocketAddress> localAddresses) {
		if (localAddresses == null) {
			throw new IllegalArgumentException("localAddresses");
		}

		boolean deactivate = false;
		synchronized (bindLock) {
			synchronized (boundAddresses) {
				if (boundAddresses.isEmpty()) {
					return;
				}

				List<SocketAddress> localAddressesCopy = new ArrayList<>();
				int specifiedAddressCount = 0;

				for (SocketAddress a : localAddresses) {
					specifiedAddressCount++;

					if ((a != null) && boundAddresses.contains(a)) {
						localAddressesCopy.add(a);
					}
				}

				if (specifiedAddressCount == 0) {
					throw new IllegalArgumentException("localAddresses is empty.");
				}

				if (!localAddressesCopy.isEmpty()) {
					try {
						unbind0(localAddressesCopy);
					} catch (RuntimeException e) {
						throw e;
					} catch (Exception e) {
						throw new RuntimeException("Failed to unbind from: " + getLocalAddresses(), e);
					}

					boundAddresses.removeAll(localAddressesCopy);

					if (boundAddresses.isEmpty()) {
						deactivate = true;
					}
				}
			}
		}

		if (deactivate) {
			fireServiceDeactivated();
		}
	}

	/**
	 * Starts the acceptor, and register the given addresses
	 *
	 * @param localAddresses The address to bind to
	 * @return the {@link Set} of the local addresses which is bound actually
	 * @throws Exception If the bind failed
	 */
	protected abstract Set<InetSocketAddress> bindInternal(List<? extends SocketAddress> localAddresses) throws Exception;

	/**
	 * Implement this method to perform the actual unbind operation.
	 *
	 * @param localAddresses The address to unbind from
	 * @throws Exception If the unbind failed
	 */
	protected abstract void unbind0(List<? extends SocketAddress> localAddresses) throws Exception;

	@Override
	public String toString() {
		return "(nio socket acceptor: " + (isActive() ? "localAddress(es): " + getLocalAddresses() +
				", managedSessionCount: " + getManagedSessionCount() : "not bound") + ')';
	}

	/**
	 * A {@link IoFuture}
	 */
	public static final class AcceptorOperationFuture extends ServiceOperationFuture {
		private final List<SocketAddress> localAddresses;

		/**
		 * Creates a new AcceptorOperationFuture instance
		 *
		 * @param localAddresses The list of local addresses to listen to
		 */
		public AcceptorOperationFuture(List<? extends SocketAddress> localAddresses) {
			this.localAddresses = new ArrayList<>(localAddresses);
		}

		/**
		 * @return The list of local addresses we listen to
		 */
		public final List<SocketAddress> getLocalAddresses() {
			return Collections.unmodifiableList(localAddresses);
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder("Acceptor operation: ");

			if (localAddresses != null) {
				boolean isFirst = true;

				for (SocketAddress address : localAddresses) {
					if (isFirst) {
						isFirst = false;
					} else {
						sb.append(", ");
					}

					sb.append(address);
				}
			}
			return sb.toString();
		}
	}
}
