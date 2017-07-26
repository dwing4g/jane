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
 * The configuration of {@link IoSession}.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public interface IoSessionConfig {

	/**
	 * @return the size of the read buffer that I/O processor allocates
	 * per each read.  It's unusual to adjust this property because
	 * it's often adjusted automatically by the I/O processor.
	 */
	int getReadBufferSize();

	/**
	 * Sets the size of the read buffer that I/O processor allocates
	 * per each read.  It's unusual to adjust this property because
	 * it's often adjusted automatically by the I/O processor.
	 *
	 * @param readBufferSize The size of the read buffer
	 */
	void setReadBufferSize(int readBufferSize);

	/**
	 * @return the minimum size of the read buffer that I/O processor
	 * allocates per each read.  I/O processor will not decrease the
	 * read buffer size to the smaller value than this property value.
	 */
	int getMinReadBufferSize();

	/**
	 * Sets the minimum size of the read buffer that I/O processor
	 * allocates per each read.  I/O processor will not decrease the
	 * read buffer size to the smaller value than this property value.
	 *
	 * @param minReadBufferSize The minimum size of the read buffer
	 */
	void setMinReadBufferSize(int minReadBufferSize);

	/**
	 * @return the maximum size of the read buffer that I/O processor
	 * allocates per each read.  I/O processor will not increase the
	 * read buffer size to the greater value than this property value.
	 */
	int getMaxReadBufferSize();

	/**
	 * Sets the maximum size of the read buffer that I/O processor
	 * allocates per each read.  I/O processor will not increase the
	 * read buffer size to the greater value than this property value.
	 *
	 * @param maxReadBufferSize The maximum size of the read buffer
	 */
	void setMaxReadBufferSize(int maxReadBufferSize);

	/**
	 * @return the interval (seconds) between each throughput calculation.
	 * The default value is <tt>3</tt> seconds.
	 */
	int getThroughputCalculationInterval();

	/**
	 * @return the interval (milliseconds) between each throughput calculation.
	 * The default value is <tt>3</tt> seconds.
	 */
	long getThroughputCalculationIntervalInMillis();

	/**
	 * Sets the interval (seconds) between each throughput calculation.  The
	 * default value is <tt>3</tt> seconds.
	 *
	 * @param throughputCalculationInterval The interval
	 */
	void setThroughputCalculationInterval(int throughputCalculationInterval);

	/**
	 * Sets all configuration properties retrieved from the specified
	 * <tt>config</tt>.
	 *
	 * @param config The configuration to use
	 */
	void setAll(IoSessionConfig config);
}
