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
 *
 */
package org.apache.mina.core.session;

/**
 * A base implementation of {@link IoSessionConfig}.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public abstract class AbstractIoSessionConfig implements IoSessionConfig {
	/** The minimum size of the buffer used to read incoming data */
	private int minReadBufferSize = 64;

	/** The default size of the buffer used to read incoming data */
	private int readBufferSize = 2048;

	/** The maximum size of the buffer used to read incoming data */
	private int maxReadBufferSize = 65536;

	private int throughputCalculationInterval = 3;

	protected AbstractIoSessionConfig() {
		// Do nothing
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setAll(IoSessionConfig config) {
		if (config == null) {
			throw new IllegalArgumentException("config");
		}

		setReadBufferSize(config.getReadBufferSize());
		setMaxReadBufferSize(config.getMaxReadBufferSize());
		setMinReadBufferSize(config.getMinReadBufferSize());
		setThroughputCalculationInterval(config.getThroughputCalculationInterval());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int getReadBufferSize() {
		return readBufferSize;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setReadBufferSize(int readBufferSize) {
		if (readBufferSize <= 0) {
			throw new IllegalArgumentException("readBufferSize: " + readBufferSize + " (expected: 1+)");
		}
		this.readBufferSize = readBufferSize;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int getMinReadBufferSize() {
		return minReadBufferSize;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setMinReadBufferSize(int minReadBufferSize) {
		if (minReadBufferSize <= 0) {
			throw new IllegalArgumentException("minReadBufferSize: " + minReadBufferSize + " (expected: 1+)");
		}
		if (minReadBufferSize > maxReadBufferSize) {
			throw new IllegalArgumentException("minReadBufferSize: " + minReadBufferSize + " (expected: smaller than "
					+ maxReadBufferSize + ')');

		}
		this.minReadBufferSize = minReadBufferSize;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int getMaxReadBufferSize() {
		return maxReadBufferSize;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setMaxReadBufferSize(int maxReadBufferSize) {
		if (maxReadBufferSize <= 0) {
			throw new IllegalArgumentException("maxReadBufferSize: " + maxReadBufferSize + " (expected: 1+)");
		}
		if (maxReadBufferSize < minReadBufferSize) {
			throw new IllegalArgumentException("maxReadBufferSize: " + maxReadBufferSize + " (expected: greater than "
					+ minReadBufferSize + ')');

		}
		this.maxReadBufferSize = maxReadBufferSize;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int getThroughputCalculationInterval() {
		return throughputCalculationInterval;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setThroughputCalculationInterval(int throughputCalculationInterval) {
		if (throughputCalculationInterval < 0) {
			throw new IllegalArgumentException("throughputCalculationInterval: " + throughputCalculationInterval);
		}

		this.throughputCalculationInterval = throughputCalculationInterval;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public long getThroughputCalculationIntervalInMillis() {
		return throughputCalculationInterval * 1000L;
	}
}
