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
package org.apache.mina.transport.socket;

import org.apache.mina.core.service.IoService;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;

/** A default implementation of {@link AbstractSocketSessionConfig}. */
public final class DefaultSocketSessionConfig extends AbstractSocketSessionConfig {
	private static final int DEFAULT_SO_LINGER = -1;
	private static final int DEFAULT_TRAFFIC_CLASS = 0;
	private static final boolean DEFAULT_REUSE_ADDRESS = false;
	private static final boolean DEFAULT_TCP_NO_DELAY = false;
	private static final boolean DEFAULT_KEEP_ALIVE = false;
	private static final boolean DEFAULT_OOB_INLINE = false;

	private int receiveBufferSize = -1; // The SO_RCVBUF parameter. Set to -1 (ie, will default to OS default)
	private int sendBufferSize = -1; // The SO_SNDBUF parameter. Set to -1 (ie, will default to OS default)
	private int soLinger = DEFAULT_SO_LINGER;
	private int trafficClass = DEFAULT_TRAFFIC_CLASS;
	private final boolean defaultReuseAddress;
	private boolean reuseAddress;
	private boolean tcpNoDelay = DEFAULT_TCP_NO_DELAY;
	private boolean keepAlive = DEFAULT_KEEP_ALIVE;
	private boolean oobInline = DEFAULT_OOB_INLINE;

	/** @param p The parent IoService. */
	public DefaultSocketSessionConfig(IoService p) {
		reuseAddress = defaultReuseAddress = (p instanceof NioSocketAcceptor || DEFAULT_REUSE_ADDRESS);
	}

	@Override
	public int getReceiveBufferSize() {
		return receiveBufferSize;
	}

	@Override
	public void setReceiveBufferSize(int receiveBufferSize) {
		this.receiveBufferSize = receiveBufferSize;
	}

	@Override
	protected boolean isReceiveBufferSizeChanged() {
		return receiveBufferSize != -1;
	}

	@Override
	public int getSendBufferSize() {
		return sendBufferSize;
	}

	@Override
	public void setSendBufferSize(int sendBufferSize) {
		this.sendBufferSize = sendBufferSize;
	}

	@Override
	protected boolean isSendBufferSizeChanged() {
		return sendBufferSize != -1;
	}

	@Override
	public int getSoLinger() {
		return soLinger;
	}

	@Override
	public void setSoLinger(int soLinger) {
		this.soLinger = soLinger;
	}

	@Override
	protected boolean isSoLingerChanged() {
		return soLinger != DEFAULT_SO_LINGER;
	}

	@Override
	public int getTrafficClass() {
		return trafficClass;
	}

	@Override
	public void setTrafficClass(int trafficClass) {
		this.trafficClass = trafficClass;
	}

	@Override
	protected boolean isTrafficClassChanged() {
		return trafficClass != DEFAULT_TRAFFIC_CLASS;
	}

	@Override
	public boolean isReuseAddress() {
		return reuseAddress;
	}

	@Override
	public void setReuseAddress(boolean reuseAddress) {
		this.reuseAddress = reuseAddress;
	}

	@Override
	protected boolean isReuseAddressChanged() {
		return reuseAddress != defaultReuseAddress;
	}

	@Override
	public boolean isTcpNoDelay() {
		return tcpNoDelay;
	}

	@Override
	public void setTcpNoDelay(boolean tcpNoDelay) {
		this.tcpNoDelay = tcpNoDelay;
	}

	@Override
	protected boolean isTcpNoDelayChanged() {
		return tcpNoDelay != DEFAULT_TCP_NO_DELAY;
	}

	@Override
	public boolean isKeepAlive() {
		return keepAlive;
	}

	@Override
	public void setKeepAlive(boolean keepAlive) {
		this.keepAlive = keepAlive;
	}

	@Override
	protected boolean isKeepAliveChanged() {
		return keepAlive != DEFAULT_KEEP_ALIVE;
	}

	@Override
	public boolean isOobInline() {
		return oobInline;
	}

	@Override
	public void setOobInline(boolean oobInline) {
		this.oobInline = oobInline;
	}

	@Override
	protected boolean isOobInlineChanged() {
		return oobInline != DEFAULT_OOB_INLINE;
	}
}
