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

import java.net.Socket;

/**
 * The TCP transport session configuration.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public abstract class AbstractSocketSessionConfig {
	/** The default size of the buffer used to read incoming data */
	private int readBufferSize = 2048;

	/** The minimum size of the buffer used to read incoming data */
	private int minReadBufferSize = 64;

	/** The maximum size of the buffer used to read incoming data */
	private int maxReadBufferSize = 65536;

	/**
	 * @return the size of the read buffer that I/O processor allocates per each read.
	 * It's unusual to adjust this property because it's often adjusted automatically by the I/O processor.
	 */
	public int getReadBufferSize() {
		return readBufferSize;
	}

	/**
	 * Sets the size of the read buffer that I/O processor allocates per each read.
	 * It's unusual to adjust this property because it's often adjusted automatically by the I/O processor.
	 *
	 * @param readBufferSize The size of the read buffer
	 */
	public void setReadBufferSize(int readBufferSize) {
		if (readBufferSize <= 0) {
			throw new IllegalArgumentException("readBufferSize: " + readBufferSize + " (expected: 1+)");
		}
		this.readBufferSize = readBufferSize;
	}

	/**
	 * @return the minimum size of the read buffer that I/O processor allocates per each read.
	 *         I/O processor will not decrease the read buffer size to the smaller value than this property value.
	 */
	public int getMinReadBufferSize() {
		return minReadBufferSize;
	}

	/**
	 * Sets the minimum size of the read buffer that I/O processor allocates per each read.
	 * I/O processor will not decrease the read buffer size to the smaller value than this property value.
	 *
	 * @param minReadBufferSize The minimum size of the read buffer
	 */
	public void setMinReadBufferSize(int minReadBufferSize) {
		if (minReadBufferSize <= 0) {
			throw new IllegalArgumentException("minReadBufferSize: " + minReadBufferSize + " (expected: 1+)");
		}
		if (minReadBufferSize > maxReadBufferSize) {
			throw new IllegalArgumentException("minReadBufferSize: " + minReadBufferSize + " (expected: smaller than " + maxReadBufferSize + ')');
		}
		this.minReadBufferSize = minReadBufferSize;
	}

	/**
	 * @return the maximum size of the read buffer that I/O processor allocates per each read.
	 * I/O processor will not increase the read buffer size to the greater value than this property value.
	 */
	public int getMaxReadBufferSize() {
		return maxReadBufferSize;
	}

	/**
	 * Sets the maximum size of the read buffer that I/O processor allocates per each read.
	 * I/O processor will not increase the read buffer size to the greater value than this property value.
	 *
	 * @param maxReadBufferSize The maximum size of the read buffer
	 */
	public void setMaxReadBufferSize(int maxReadBufferSize) {
		if (maxReadBufferSize <= 0) {
			throw new IllegalArgumentException("maxReadBufferSize: " + maxReadBufferSize + " (expected: 1+)");
		}
		if (maxReadBufferSize < minReadBufferSize) {
			throw new IllegalArgumentException("maxReadBufferSize: " + maxReadBufferSize + " (expected: greater than " + minReadBufferSize + ')');
		}
		this.maxReadBufferSize = maxReadBufferSize;
	}

	/**
	 * Sets all configuration properties retrieved from the specified <tt>config</tt>.
	 *
	 * @param config The configuration to use
	 */
	public void setAll(AbstractSocketSessionConfig config) {
		if (config == null) {
			throw new IllegalArgumentException("config");
		}

		setReadBufferSize(config.getReadBufferSize());
		setMinReadBufferSize(config.getMinReadBufferSize());
		setMaxReadBufferSize(config.getMaxReadBufferSize());

		// Minimize unnecessary system calls by checking all 'propertyChanged' properties.
		if (config.isReceiveBufferSizeChanged()) {
			setReceiveBufferSize(config.getReceiveBufferSize());
		}
		if (config.isSendBufferSizeChanged()) {
			setSendBufferSize(config.getSendBufferSize());
		}
		if (config.isSoLingerChanged()) {
			setSoLinger(config.getSoLinger());
		}
		if (config.isTrafficClassChanged() && getTrafficClass() != config.getTrafficClass()) {
			setTrafficClass(config.getTrafficClass());
		}
		if (config.isReuseAddressChanged()) {
			setReuseAddress(config.isReuseAddress());
		}
		if (config.isTcpNoDelayChanged()) {
			setTcpNoDelay(config.isTcpNoDelay());
		}
		if (config.isKeepAliveChanged()) {
			setKeepAlive(config.isKeepAlive());
		}
		if (config.isOobInlineChanged()) {
			setOobInline(config.isOobInline());
		}
	}

	/**
	 * @see Socket#getReceiveBufferSize()
	 *
	 * @return the size of the receive buffer
	 */
	public abstract int getReceiveBufferSize();

	/**
	 * @see Socket#setReceiveBufferSize(int)
	 *
	 * @param receiveBufferSize The size of the receive buffer
	 */
	public abstract void setReceiveBufferSize(int receiveBufferSize);

	/**
	 * @see Socket#getSendBufferSize()
	 *
	 * @return the size of the send buffer
	 */
	public abstract int getSendBufferSize();

	/**
	 * @see Socket#setSendBufferSize(int)
	 *
	 * @param sendBufferSize The size of the send buffer
	 */
	public abstract void setSendBufferSize(int sendBufferSize);

	/**
	 * Please note that enabling <tt>SO_LINGER</tt> in Java NIO can result
	 * in platform-dependent behavior and unexpected blocking of I/O thread.
	 *
	 * @see Socket#getSoLinger()
	 * @see <a href="http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6179351">Sun Bug Database</a>
	 *
	 * @return The value for <tt>SO_LINGER</tt>
	 */
	public abstract int getSoLinger();

	/**
	 * Please note that enabling <tt>SO_LINGER</tt> in Java NIO can result
	 * in platform-dependent behavior and unexpected blocking of I/O thread.
	 *
	 * @param soLinger Please specify a negative value to disable <tt>SO_LINGER</tt>.
	 *
	 * @see Socket#setSoLinger(boolean, int)
	 * @see <a href="http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6179351">Sun Bug Database</a>
	 */
	public abstract void setSoLinger(int soLinger);

	/**
	 * @see Socket#getTrafficClass()
	 *
	 * @return the traffic class
	 */
	public abstract int getTrafficClass();

	/**
	 * @see Socket#setTrafficClass(int)
	 *
	 * @param trafficClass The traffic class to set, one of <tt>IPTOS_LOWCOST</tt> (0x02)
	 * <tt>IPTOS_RELIABILITY</tt> (0x04), <tt>IPTOS_THROUGHPUT</tt> (0x08) or <tt>IPTOS_LOWDELAY</tt> (0x10)
	 */
	public abstract void setTrafficClass(int trafficClass);

	/**
	 * @see Socket#getReuseAddress()
	 *
	 * @return <tt>true</tt> if SO_REUSEADDR is enabled.
	 */
	public abstract boolean isReuseAddress();

	/**
	 * @see Socket#setReuseAddress(boolean)
	 *
	 * @param reuseAddress Tells if SO_REUSEADDR is enabled or disabled
	 */
	public abstract void setReuseAddress(boolean reuseAddress);

	/**
	 * @see Socket#getTcpNoDelay()
	 *
	 * @return <tt>true</tt> if <tt>TCP_NODELAY</tt> is enabled.
	 */
	public abstract boolean isTcpNoDelay();

	/**
	 * @see Socket#setTcpNoDelay(boolean)
	 *
	 * @param tcpNoDelay <tt>true</tt> if <tt>TCP_NODELAY</tt> is to be enabled
	 */
	public abstract void setTcpNoDelay(boolean tcpNoDelay);

	/**
	 * @see Socket#getKeepAlive()
	 *
	 * @return <tt>true</tt> if <tt>SO_KEEPALIVE</tt> is enabled.
	 */
	public abstract boolean isKeepAlive();

	/**
	 * @see Socket#setKeepAlive(boolean)
	 *
	 * @param keepAlive if <tt>SO_KEEPALIVE</tt> is to be enabled
	 */
	public abstract void setKeepAlive(boolean keepAlive);

	/**
	 * @see Socket#getOOBInline()
	 *
	 * @return <tt>true</tt> if <tt>SO_OOBINLINE</tt> is enabled.
	 */
	public abstract boolean isOobInline();

	/**
	 * @see Socket#setOOBInline(boolean)
	 *
	 * @param oobInline if <tt>SO_OOBINLINE</tt> is to be enabled
	 */
	public abstract void setOobInline(boolean oobInline);

	/**
	 * @return <tt>true</tt> if and only if the <tt>receiveBufferSize</tt> property
	 * has been changed by its setter method.  The system call related with
	 * the property is made only when this method returns <tt>true</tt>.  By
	 * default, this method always returns <tt>true</tt> to simplify implementation
	 * of subclasses, but overriding the default behavior is always encouraged.
	 */
	@SuppressWarnings("static-method")
	protected boolean isReceiveBufferSizeChanged() {
		return true;
	}

	/**
	 * @return <tt>true</tt> if and only if the <tt>sendBufferSize</tt> property
	 * has been changed by its setter method.  The system call related with
	 * the property is made only when this method returns <tt>true</tt>.  By
	 * default, this method always returns <tt>true</tt> to simplify implementation
	 * of subclasses, but overriding the default behavior is always encouraged.
	 */
	@SuppressWarnings("static-method")
	protected boolean isSendBufferSizeChanged() {
		return true;
	}

	/**
	 * @return <tt>true</tt> if and only if the <tt>soLinger</tt> property
	 * has been changed by its setter method.  The system call related with
	 * the property is made only when this method returns <tt>true</tt>.  By
	 * default, this method always returns <tt>true</tt> to simplify implementation
	 * of subclasses, but overriding the default behavior is always encouraged.
	 */
	@SuppressWarnings("static-method")
	protected boolean isSoLingerChanged() {
		return true;
	}

	/**
	 * @return <tt>true</tt> if and only if the <tt>trafficClass</tt> property
	 * has been changed by its setter method.  The system call related with
	 * the property is made only when this method returns <tt>true</tt>.  By
	 * default, this method always returns <tt>true</tt> to simplify implementation
	 * of subclasses, but overriding the default behavior is always encouraged.
	 */
	@SuppressWarnings("static-method")
	protected boolean isTrafficClassChanged() {
		return true;
	}

	/**
	 * @return <tt>true</tt> if and only if the <tt>reuseAddress</tt> property
	 * has been changed by its setter method.  The system call related with
	 * the property is made only when this method returns <tt>true</tt>.  By
	 * default, this method always returns <tt>true</tt> to simplify implementation
	 * of subclasses, but overriding the default behavior is always encouraged.
	 */
	@SuppressWarnings("static-method")
	protected boolean isReuseAddressChanged() {
		return true;
	}

	/**
	 * @return <tt>true</tt> if and only if the <tt>tcpNoDelay</tt> property
	 * has been changed by its setter method.  The system call related with
	 * the property is made only when this method returns <tt>true</tt>.  By
	 * default, this method always returns <tt>true</tt> to simplify implementation
	 * of subclasses, but overriding the default behavior is always encouraged.
	 */
	@SuppressWarnings("static-method")
	protected boolean isTcpNoDelayChanged() {
		return true;
	}

	/**
	 * @return <tt>true</tt> if and only if the <tt>keepAlive</tt> property
	 * has been changed by its setter method.  The system call related with
	 * the property is made only when this method returns <tt>true</tt>.  By
	 * default, this method always returns <tt>true</tt> to simplify implementation
	 * of subclasses, but overriding the default behavior is always encouraged.
	 */
	@SuppressWarnings("static-method")
	protected boolean isKeepAliveChanged() {
		return true;
	}

	/**
	 * @return <tt>true</tt> if and only if the <tt>oobInline</tt> property
	 * has been changed by its setter method.  The system call related with
	 * the property is made only when this method returns <tt>true</tt>.  By
	 * default, this method always returns <tt>true</tt> to simplify implementation
	 * of subclasses, but overriding the default behavior is always encouraged.
	 */
	@SuppressWarnings("static-method")
	protected boolean isOobInlineChanged() {
		return true;
	}
}
