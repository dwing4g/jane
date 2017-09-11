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
package org.apache.mina.core.buffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.DefaultWriteRequest;
import org.apache.mina.core.write.WriteRequest;

/**
 * A byte buffer used by MINA applications.
 * <p>
 * This is a replacement for {@link ByteBuffer}. Please refer to
 * {@link ByteBuffer} documentation for preliminary usage. MINA does not use NIO
 * {@link ByteBuffer} directly for two reasons:
 * <ul>
 *   <li>It doesn't provide useful getters and putters such as <code>fill</code>,
 *       <code>get/putString</code>, and <code>get/putAsciiInt()</code> enough.</li>
 *   <li>It is difficult to write variable-length data due to its fixed capacity</li>
 * </ul>
 *
 * <h2>Allocation</h2>
 * <p>
 *   You can allocate a new heap buffer.
 *
 *   <pre>
 *     IoBuffer buf = IoBuffer.allocate(1024, false);
 *   </pre>
 *
 *   You can also allocate a new direct buffer:
 *
 *   <pre>
 *     IoBuffer buf = IoBuffer.allocate(1024, true);
 *   </pre>
 *
 *   or you can set the default buffer type.
 *
 *   <pre>
 *     // Allocate heap buffer by default.
 *     IoBuffer.setUseDirectBuffer(false);
 *
 *     // A new heap buffer is returned.
 *     IoBuffer buf = IoBuffer.allocate(1024);
 *   </pre>
 *
 * <h2>Wrapping existing NIO buffers and arrays</h2>
 * <p>
 *   This class provides a few <tt>wrap(...)</tt> methods that wraps any NIO
 *   buffers and byte arrays.
 *
 * <h2>AutoExpand</h2>
 * <p>
 *   Writing variable-length data using NIO <tt>ByteBuffers</tt> is not really
 *   easy, and it is because its size is fixed at allocation. {@link IoBuffer} introduces
 *   the <tt>autoExpand</tt> property. If <tt>autoExpand</tt> property is set to true,
 *   you never get a {@link BufferOverflowException} or
 *   an {@link IndexOutOfBoundsException} (except when index is negative). It
 *   automatically expands its capacity. For instance:
 *
 *   <pre>
 *     String greeting = messageBundle.getMessage(&quot;hello&quot;);
 *     IoBuffer buf = IoBuffer.allocate(16);
 *     // Turn on autoExpand (it is off by default)
 *     buf.setAutoExpand(true);
 *     buf.putString(greeting, utf8encoder);
 *   </pre>
 *
 *   The underlying {@link ByteBuffer} is reallocated by {@link IoBuffer} behind
 *   the scene if the encoded data is larger than 16 bytes in the example above.
 *   Its capacity will double, and its limit will increase to the last position
 *   the string is written.
 *
 * <h2>AutoShrink</h2>
 * <p>
 *   You might also want to decrease the capacity of the buffer when most of the
 *   allocated memory area is not being used. {@link IoBuffer} provides
 *   <tt>autoShrink</tt> property to take care of this issue. If
 *   <tt>autoShrink</tt> is turned on, {@link IoBuffer} halves the capacity of the
 *   buffer when {@link #compact()} is invoked and only 1/4 or less of the current
 *   capacity is being used.
 * <p>
 *   You can also call the {@link #shrink()} method manually to shrink the capacity of the
 *   buffer.
 * <p>
 *   The underlying {@link ByteBuffer} is reallocated by the {@link IoBuffer} behind
 *   the scene, and therefore {@link #buf()} will return a different
 *   {@link ByteBuffer} instance once capacity changes. Please also note
 *   that the {@link #compact()} method or the {@link #shrink()} method
 *   will not decrease the capacity if the new capacity is less than the
 *   {@link #minimumCapacity()} of the buffer.
 *
 * <h2>Derived Buffers</h2>
 * <p>
 *   Derived buffers are the buffers which were created by the {@link #duplicate()} or
 *   {@link #slice()} methods. They are useful especially
 *   when you broadcast the same messages to multiple {@link IoSession}s. Please
 *   note that the buffer derived from and its derived buffers are not
 *   auto-expandable nor auto-shrinkable. Trying to call
 *   {@link #setAutoExpand(boolean)} or {@link #setAutoShrink(boolean)} with
 *   <tt>true</tt> parameter will raise an {@link IllegalStateException}.
 *
 * <h2>Changing Buffer Allocation Policy</h2>
 * <p>
 *   The {@link IoBufferAllocator} interface lets you override the default buffer
 *   management behavior. There is only one allocator provided out-of-the-box:
 *   <ul>
 *     <li>{@link SimpleBufferAllocator} (default)</li>
 *   </ul>
 *   You can implement your own allocator and use it by calling
 *   {@link #setAllocator(IoBufferAllocator)}.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public abstract class IoBuffer implements Comparable<IoBuffer>, WriteRequest {
	/** The allocator used to create new buffers */
	private static IoBufferAllocator allocator = new SimpleBufferAllocator();

	/** A flag indicating which type of buffer we are using: heap or direct */
	private static boolean useDirectBuffer;

	/**
	 * Creates a new instance. This is an empty constructor. It's protected,
	 * to forbid its usage by the users.
	 */
	protected IoBuffer() {
		// Do nothing
	}

	@Override
	public final Object getMessage() {
		return this;
	}

	@Override
	public final WriteRequest getOriginalRequest() {
		return this;
	}

	@Override
	public final WriteFuture getFuture() {
		return DefaultWriteRequest.UNUSED_FUTURE;
	}

	/**
	 * @return the allocator used by existing and new buffers
	 */
	public static IoBufferAllocator getAllocator() {
		return allocator;
	}

	/**
	 * Sets the allocator used by existing and new buffers
	 *
	 * @param newAllocator the new allocator to use
	 */
	public static void setAllocator(IoBufferAllocator newAllocator) {
		if (newAllocator == null) {
			throw new IllegalArgumentException("allocator");
		}

		IoBufferAllocator oldAllocator = allocator;

		allocator = newAllocator;

		if (oldAllocator != null) {
			oldAllocator.dispose();
		}
	}

	/**
	 * @return <tt>true</tt> if and only if a direct buffer is allocated by
	 * default when the type of the new buffer is not specified.
	 * The default value is <tt>false</tt>.
	 */
	public static boolean isUseDirectBuffer() {
		return useDirectBuffer;
	}

	/**
	 * Sets if a direct buffer should be allocated by default when the type of
	 * the new buffer is not specified. The default value is <tt>false</tt>.
	 *
	 * @param useDirectBuffer Tells if direct buffers should be allocated
	 */
	public static void setUseDirectBuffer(boolean useDirectBuffer) {
		IoBuffer.useDirectBuffer = useDirectBuffer;
	}

	/**
	 * Returns the direct or heap buffer which is capable to store the specified amount of bytes.
	 *
	 * @param capacity the capacity of the buffer
	 * @return a IoBuffer which can hold up to capacity bytes
	 *
	 * @see #setUseDirectBuffer(boolean)
	 */
	public static IoBuffer allocate(int capacity) {
		return allocate(capacity, useDirectBuffer);
	}

	/**
	 * Returns a direct or heap IoBuffer which can contain the specified number of bytes.
	 *
	 * @param capacity the capacity of the buffer
	 * @param isDirectBuffer <tt>true</tt> to get a direct buffer,
	 *                       <tt>false</tt> to get a heap buffer.
	 * @return a direct or heap  IoBuffer which can hold up to capacity bytes
	 */
	public static IoBuffer allocate(int capacity, boolean isDirectBuffer) {
		if (capacity < 0) {
			throw new IllegalArgumentException("capacity: " + capacity);
		}

		return allocator.allocate(capacity, isDirectBuffer);
	}

	/**
	 * Wraps the specified NIO {@link ByteBuffer} into a MINA buffer (either direct or heap).
	 *
	 * @param nioBuffer The {@link ByteBuffer} to wrap
	 * @return a IoBuffer containing the bytes stored in the {@link ByteBuffer}
	 */
	public static IoBuffer wrap(ByteBuffer nioBuffer) {
		return allocator.wrap(nioBuffer);
	}

	/**
	 * Wraps the specified byte array into a MINA heap buffer.
	 * Note that the byte array is not copied,
	 * so any modification done on it will be visible by both sides.
	 *
	 * @param byteArray The byte array to wrap
	 * @return a heap IoBuffer containing the byte array
	 */
	public static IoBuffer wrap(byte[] byteArray) {
		return wrap(ByteBuffer.wrap(byteArray));
	}

	/**
	 * Wraps the specified byte array into MINA heap buffer.
	 * We just wrap the bytes starting from offset up to offset + length.
	 * Note that the byte array is not copied,
	 * so any modification done on it will be visible by both sides.
	 *
	 * @param byteArray The byte array to wrap
	 * @param offset The starting point in the byte array
	 * @param length The number of bytes to store
	 * @return a heap IoBuffer containing the selected part of the byte array
	 */
	public static IoBuffer wrap(byte[] byteArray, int offset, int length) {
		return wrap(ByteBuffer.wrap(byteArray, offset, length));
	}

	/**
	 * Normalizes the specified capacity of the buffer to power of 2,
	 * which is often helpful for optimal memory usage and performance.
	 * If it is greater than or equal to {@link Integer#MAX_VALUE}, it returns {@link Integer#MAX_VALUE}.
	 * If it is zero, it returns zero.
	 *
	 * @param requestedCapacity The IoBuffer capacity we want to be able to store
	 * @return The  power of 2 strictly superior to the requested capacity
	 */
	protected static int normalizeCapacity(int requestedCapacity) {
		if (requestedCapacity < 0) {
			return Integer.MAX_VALUE;
		}

		int newCapacity = Integer.highestOneBit(requestedCapacity);
		newCapacity <<= (newCapacity < requestedCapacity ? 1 : 0);

		return newCapacity < 0 ? Integer.MAX_VALUE : newCapacity;
	}

	/**
	 * Declares this buffer and all its derived buffers are not used anymore
	 * so that it can be reused by some {@link IoBufferAllocator} implementations.
	 * It is not mandatory to call this method,
	 * but you might want to invoke this method for maximum performance.
	 */
	public abstract void free();

	/**
	 * @return the underlying NIO {@link ByteBuffer} instance.
	 */
	public abstract ByteBuffer buf();

	/**
	 * @see ByteBuffer#isDirect()
	 *
	 * @return <tt>True</tt> if this is a direct buffer
	 */
	public abstract boolean isDirect();

	/**
	 * @return <tt>true</tt> if and only if this buffer is derived from another
	 * buffer via one of the {@link #duplicate()} or {@link #slice()} methods.
	 */
	public abstract boolean isDerived();

	/**
	 * @return the minimum capacity of this buffer which is used to determine
	 * the new capacity of the buffer shrunk by the {@link #compact()} and {@link #shrink()} operation.
	 * The default value is the initial capacity of the buffer.
	 */
	public abstract int minimumCapacity();

	/**
	 * Sets the minimum capacity of this buffer which is used to determine the
	 * new capacity of the buffer shrunk by {@link #compact()} and {@link #shrink()} operation.
	 * The default value is the initial capacity of the buffer.
	 *
	 * @param minimumCapacity the wanted minimum capacity
	 * @return the underlying NIO {@link ByteBuffer} instance.
	 */
	public abstract IoBuffer minimumCapacity(int minimumCapacity);

	/**
	 * @see ByteBuffer#capacity()
	 *
	 * @return the buffer capacity
	 */
	public abstract int capacity();

	/**
	 * Increases the capacity of this buffer. If the new capacity is less than
	 * or equal to the current capacity, this method returns the original buffer.
	 * If the new capacity is greater than the current capacity, the buffer is
	 * reallocated while retaining the position, limit, mark and the content of the buffer.
	 * <br>
	 * Note that the IoBuffer is replaced, it's not copied.
	 * <br>
	 * Assuming a buffer contains N bytes, its position is 0 and its current capacity is C,
	 * here are the resulting buffer if we set the new capacity to a value V &lt; C and V &gt; C :
	 *
	 * <pre>
	 *  Initial buffer :
	 *
	 *   0       L          C
	 *  +--------+----------+
	 *  |XXXXXXXX|          |
	 *  +--------+----------+
	 *   ^       ^          ^
	 *   |       |          |
	 *  pos    limit     capacity
	 *
	 * V &lt;= C :
	 *
	 *   0       L          C
	 *  +--------+----------+
	 *  |XXXXXXXX|          |
	 *  +--------+----------+
	 *   ^       ^          ^
	 *   |       |          |
	 *  pos    limit   newCapacity
	 *
	 * V &gt; C :
	 *
	 *   0       L          C            V
	 *  +--------+-----------------------+
	 *  |XXXXXXXX|          :            |
	 *  +--------+-----------------------+
	 *   ^       ^          ^            ^
	 *   |       |          |            |
	 *  pos    limit   oldCapacity  newCapacity
	 *
	 *  The buffer has been increased.
	 *
	 * </pre>
	 *
	 * @param newCapacity the wanted capacity
	 * @return the underlying NIO {@link ByteBuffer} instance.
	 */
	public abstract IoBuffer capacity(int newCapacity);

	/**
	 * @return <tt>true</tt> if and only if <tt>autoExpand</tt> is turned on.
	 */
	public abstract boolean isAutoExpand();

	/**
	 * Turns on or off <tt>autoExpand</tt>.
	 *
	 * @param autoExpand The flag value to set
	 * @return The modified IoBuffer instance
	 */
	public abstract IoBuffer setAutoExpand(boolean autoExpand);

	/**
	 * @return <tt>true</tt> if and only if <tt>autoShrink</tt> is turned on.
	 */
	public abstract boolean isAutoShrink();

	/**
	 * Turns on or off <tt>autoShrink</tt>.
	 *
	 * @param autoShrink The flag value to set
	 * @return The modified IoBuffer instance
	 */
	public abstract IoBuffer setAutoShrink(boolean autoShrink);

	/**
	 * Changes the capacity and limit of this buffer so this buffer get the
	 * specified <tt>expectedRemaining</tt> room from the current position. This
	 * method works even if you didn't set <tt>autoExpand</tt> to <tt>true</tt>.
	 * <br>
	 * Assuming a buffer contains N bytes, its position is P and its current capacity is C,
	 * here are the resulting buffer if we call the expand method with a expectedRemaining value V:
	 *
	 * <pre>
	 *  Initial buffer :
	 *
	 *   0       L          C
	 *  +--------+----------+
	 *  |XXXXXXXX|          |
	 *  +--------+----------+
	 *   ^       ^          ^
	 *   |       |          |
	 *  pos    limit     capacity
	 *
	 * ( pos + V )  &lt;= L, no change :
	 *
	 *   0       L          C
	 *  +--------+----------+
	 *  |XXXXXXXX|          |
	 *  +--------+----------+
	 *   ^       ^          ^
	 *   |       |          |
	 *  pos    limit   newCapacity
	 *
	 * You can still put ( L - pos ) bytes in the buffer
	 *
	 * ( pos + V ) &gt; L &amp; ( pos + V ) &lt;= C :
	 *
	 *  0        L          C
	 *  +------------+------+
	 *  |XXXXXXXX:...|      |
	 *  +------------+------+
	 *   ^           ^      ^
	 *   |           |      |
	 *  pos       newlimit  newCapacity
	 *
	 *  You can now put ( L - pos + V )  bytes in the buffer.
	 *
	 *
	 *  ( pos + V ) &gt; C
	 *
	 *   0       L          C
	 *  +-------------------+----+
	 *  |XXXXXXXX:..........:....|
	 *  +------------------------+
	 *   ^                       ^
	 *   |                       |
	 *  pos                      +-- newlimit
	 *                           |
	 *                           +-- newCapacity
	 *
	 * You can now put ( L - pos + V ) bytes in the buffer, which limit is now
	 * equals to the capacity.
	 * </pre>
	 *
	 * Note that the expecting remaining bytes starts at the current position. In all
	 * those examples, the position is 0.
	 *
	 * @param expectedRemaining The expected remaining bytes in the buffer
	 * @return The modified IoBuffer instance
	 */
	public abstract IoBuffer expand(int expectedRemaining);

	/**
	 * Changes the capacity and limit of this buffer so this buffer get the
	 * specified <tt>expectedRemaining</tt> room from the specified
	 * <tt>position</tt>. This method works even if you didn't set
	 * <tt>autoExpand</tt> to <tt>true</tt>.
	 * Assuming a buffer contains N bytes, its position is P and its current capacity is C,
	 * here are the resulting buffer if we call the expand method with a expectedRemaining value V:
	 *
	 * <pre>
	 *  Initial buffer :
	 *
	 *      P    L          C
	 *  +--------+----------+
	 *  |XXXXXXXX|          |
	 *  +--------+----------+
	 *      ^    ^          ^
	 *      |    |          |
	 *     pos limit     capacity
	 *
	 * ( pos + V )  &lt;= L, no change :
	 *
	 *      P    L          C
	 *  +--------+----------+
	 *  |XXXXXXXX|          |
	 *  +--------+----------+
	 *      ^    ^          ^
	 *      |    |          |
	 *     pos limit   newCapacity
	 *
	 * You can still put ( L - pos ) bytes in the buffer
	 *
	 * ( pos + V ) &gt; L &amp; ( pos + V ) &lt;= C :
	 *
	 *      P    L          C
	 *  +------------+------+
	 *  |XXXXXXXX:...|      |
	 *  +------------+------+
	 *      ^        ^      ^
	 *      |        |      |
	 *     pos    newlimit  newCapacity
	 *
	 *  You can now put ( L - pos + V)  bytes in the buffer.
	 *
	 *
	 *  ( pos + V ) &gt; C
	 *
	 *      P       L          C
	 *  +-------------------+----+
	 *  |XXXXXXXX:..........:....|
	 *  +------------------------+
	 *      ^                    ^
	 *      |                    |
	 *     pos                   +-- newlimit
	 *                           |
	 *                           +-- newCapacity
	 *
	 * You can now put ( L - pos + V ) bytes in the buffer, which limit is now equals to the capacity.
	 * </pre>
	 *
	 * Note that the expecting remaining bytes starts at the current position.
	 * In all those examples, the position is P.
	 *
	 * @param position The starting position from which we want to define a remaining number of bytes
	 * @param expectedRemaining The expected remaining bytes in the buffer
	 * @return The modified IoBuffer instance
	 */
	public abstract IoBuffer expand(int position, int expectedRemaining);

	/**
	 * Changes the capacity of this buffer so this buffer occupies as less
	 * memory as possible while retaining the position, limit and the buffer
	 * content between the position and limit.
	 * <br>
	 * <b>The capacity of the buffer never becomes less than {@link #minimumCapacity()}</b>
	 * <br>.
	 * The mark is discarded once the capacity changes.
	 * <br>
	 * Typically, a call to this method tries to remove as much unused bytes
	 * as possible, dividing by two the initial capacity until it can't without
	 * obtaining a new capacity lower than the {@link #minimumCapacity()}. For instance, if
	 * the limit is 7 and the capacity is 36, with a minimum capacity of 8,
	 * shrinking the buffer will left a capacity of 9 (we go down from 36 to 18, then from 18 to 9).
	 *
	 * <pre>
	 *  Initial buffer :
	 *
	 *  +--------+----------+
	 *  |XXXXXXXX|          |
	 *  +--------+----------+
	 *      ^    ^  ^       ^
	 *      |    |  |       |
	 *     pos   |  |    capacity
	 *           |  |
	 *           |  +-- minimumCapacity
	 *           |
	 *           +-- limit
	 *
	 * Resulting buffer :
	 *
	 *  +--------+--+-+
	 *  |XXXXXXXX|  | |
	 *  +--------+--+-+
	 *      ^    ^  ^ ^
	 *      |    |  | |
	 *      |    |  | +-- new capacity
	 *      |    |  |
	 *     pos   |  +-- minimum capacity
	 *           |
	 *           +-- limit
	 * </pre>
	 *
	 * @return The modified IoBuffer instance
	 */
	public abstract IoBuffer shrink();

	/**
	 * @see java.nio.Buffer#position()
	 * @return The current position in the buffer
	 */
	public abstract int position();

	/**
	 * @see java.nio.Buffer#position(int)
	 *
	 * @param newPosition Sets the new position in the buffer
	 * @return the modified IoBuffer

	 */
	public abstract IoBuffer position(int newPosition);

	/**
	 * @see java.nio.Buffer#limit()
	 *
	 * @return the modified IoBuffer's limit
	 */
	public abstract int limit();

	/**
	 * @see java.nio.Buffer#limit(int)
	 *
	 * @param newLimit The new buffer's limit
	 * @return the modified IoBuffer

	 */
	public abstract IoBuffer limit(int newLimit);

	/**
	 * @see java.nio.Buffer#mark()
	 *
	 * @return the modified IoBuffer

	 */
	public abstract IoBuffer mark();

	/**
	 * @return the position of the current mark. This method returns <tt>-1</tt> if no mark is set.
	 */
	public abstract int markValue();

	/**
	 * @see java.nio.Buffer#reset()
	 *
	 * @return the modified IoBuffer

	 */
	public abstract IoBuffer reset();

	/**
	 * @see java.nio.Buffer#clear()
	 *
	 * @return the modified IoBuffer

	 */
	public abstract IoBuffer clear();

	/**
	 * Clears this buffer and fills its content with <tt>NUL</tt>.
	 * The position is set to zero, the limit is set to the capacity, and the mark is discarded.
	 *
	 * @return the modified IoBuffer

	 */
	public abstract IoBuffer sweep();

	/**
	 * double Clears this buffer and fills its content with <tt>value</tt>.
	 * The position is set to zero, the limit is set to the capacity, and the mark is discarded.
	 *
	 * @param value The value to put in the buffer
	 * @return the modified IoBuffer

	 */
	public abstract IoBuffer sweep(byte value);

	/**
	 * @see java.nio.Buffer#flip()
	 *
	 * @return the modified IoBuffer

	 */
	public abstract IoBuffer flip();

	/**
	 * @see java.nio.Buffer#rewind()
	 *
	 * @return the modified IoBuffer

	 */
	public abstract IoBuffer rewind();

	/**
	 * @see java.nio.Buffer#remaining()
	 *
	 * @return The remaining bytes in the buffer
	 */
	public abstract int remaining();

	/**
	 * @see java.nio.Buffer#hasRemaining()
	 *
	 * @return <tt>true</tt> if there are some remaining bytes in the buffer
	 */
	public abstract boolean hasRemaining();

	/**
	 * @see ByteBuffer#duplicate()
	 *
	 * @return the modified IoBuffer

	 */
	public abstract IoBuffer duplicate();

	/**
	 * @see ByteBuffer#slice()
	 *
	 * @return the modified IoBuffer

	 */
	public abstract IoBuffer slice();

	/**
	 * @see ByteBuffer#hasArray()
	 *
	 * @return <tt>true</tt> if the {@link #array()} method will return a byte[]
	 */
	public abstract boolean hasArray();

	/**
	 * @see ByteBuffer#array()
	 *
	 * @return A byte[] if this IoBuffer supports it
	 */
	public abstract byte[] array();

	/**
	 * @see ByteBuffer#arrayOffset()
	 *
	 * @return The offset in the returned byte[] when the {@link #array()} method is called
	 */
	public abstract int arrayOffset();

	/**
	 * @see ByteBuffer#get()
	 *
	 * @return The byte at the current position
	 */
	public abstract byte get();

	/**
	 * Reads one unsigned byte as a integer.
	 *
	 * @return the unsigned byte at the current position
	 */
	public abstract int getUnsigned();

	/**
	 * @see ByteBuffer#put(byte)
	 *
	 * @param b The byte to put in the buffer
	 * @return the modified IoBuffer

	 */
	public abstract IoBuffer put(byte b);

	/**
	 * @see ByteBuffer#get(int)
	 *
	 * @param index The position for which we want to read a byte
	 * @return the byte at the given position
	 */
	public abstract byte get(int index);

	/**
	 * Reads one byte as an unsigned integer.
	 *
	 * @param index The position for which we want to read an unsigned byte
	 * @return the unsigned byte at the given position
	 */
	public abstract int getUnsigned(int index);

	/**
	 * @see ByteBuffer#put(int, byte)
	 *
	 * @param index The position where the byte will be put
	 * @param b The byte to put
	 * @return the modified IoBuffer

	 */
	public abstract IoBuffer put(int index, byte b);

	/**
	 * @see ByteBuffer#get(byte[], int, int)
	 *
	 * @param dst The destination buffer
	 * @param offset The position in the original buffer
	 * @param length The number of bytes to copy
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer get(byte[] dst, int offset, int length);

	/**
	 * @see ByteBuffer#get(byte[])
	 *
	 * @param dst The byte[] that will contain the read bytes
	 * @return the IoBuffer
	 */
	public abstract IoBuffer get(byte[] dst);

	/**
	 * Get a new IoBuffer containing a slice of the current buffer
	 *
	 * @param index The position in the buffer
	 * @param length The number of bytes to copy
	 * @return the new IoBuffer
	 */
	public abstract IoBuffer getSlice(int index, int length);

	/**
	 * Get a new IoBuffer containing a slice of the current buffer
	 *
	 * @param length The number of bytes to copy
	 * @return the new IoBuffer
	 */
	public abstract IoBuffer getSlice(int length);

	/**
	 * Writes the content of the specified <tt>src</tt> into this buffer.
	 *
	 * @param src The source ByteBuffer
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer put(ByteBuffer src);

	/**
	 * Writes the content of the specified <tt>src</tt> into this buffer.
	 *
	 * @param src The source IoBuffer
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer put(IoBuffer src);

	/**
	 * @see ByteBuffer#put(byte[], int, int)
	 *
	 * @param src The byte[] to put
	 * @param offset The position in the source
	 * @param length The number of bytes to copy
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer put(byte[] src, int offset, int length);

	/**
	 * @see ByteBuffer#put(byte[])
	 *
	 * @param src The byte[] to put
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer put(byte[] src);

	/**
	 * @see ByteBuffer#compact()
	 *
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer compact();

	/**
	 * @see ByteBuffer#order()
	 *
	 * @return the IoBuffer ByteOrder
	 */
	public abstract ByteOrder order();

	/**
	 * @see ByteBuffer#order(ByteOrder)
	 *
	 * @param bo The new ByteBuffer to use for this IoBuffer
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer order(ByteOrder bo);

	/**
	 * @see ByteBuffer#getChar()
	 *
	 * @return The char at the current position
	 */
	public abstract char getChar();

	/**
	 * @see ByteBuffer#putChar(char)
	 *
	 * @param value The char to put at the current position
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putChar(char value);

	/**
	 * @see ByteBuffer#getChar(int)
	 *
	 * @param index The index in the IoBuffer where we will read a char from
	 * @return the char at 'index' position
	 */
	public abstract char getChar(int index);

	/**
	 * @see ByteBuffer#putChar(int, char)
	 *
	 * @param index The index in the IoBuffer where we will put a char in
	 * @param value The char to put at the current position
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putChar(int index, char value);

	/**
	 * @see ByteBuffer#getShort()
	 *
	 * @return The read short
	 */
	public abstract short getShort();

	/**
	 * Reads two bytes unsigned integer.
	 *
	 * @return The read unsigned short
	 */
	public abstract int getUnsignedShort();

	/**
	 * @see ByteBuffer#putShort(short)
	 *
	 * @param value The short to put at the current position
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putShort(short value);

	/**
	 * @see ByteBuffer#getShort()
	 *
	 * @param index The index in the IoBuffer where we will read a short from
	 * @return The read short
	 */
	public abstract short getShort(int index);

	/**
	 * Reads two bytes unsigned integer.
	 *
	 * @param index The index in the IoBuffer where we will read an unsigned short from
	 * @return the unsigned short at the given position
	 */
	public abstract int getUnsignedShort(int index);

	/**
	 * @see ByteBuffer#putShort(int, short)
	 *
	 * @param index The position at which the short should be written
	 * @param value The short to put at the current position
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putShort(int index, short value);

	/**
	 * @see ByteBuffer#getInt()
	 *
	 * @return The int read
	 */
	public abstract int getInt();

	/**
	 * Reads four bytes unsigned integer.
	 *
	 * @return The unsigned int read
	 */
	public abstract long getUnsignedInt();

	/**
	 * Relative <i>get</i> method for reading a medium int value.
	 * <p>
	 * Reads the next three bytes at this buffer's current position, composing
	 * them into an int value according to the current byte order, and then
	 * increments the position by three.
	 *
	 * @return The medium int value at the buffer's current position
	 */
	public abstract int getMediumInt();

	/**
	 * Relative <i>get</i> method for reading an unsigned medium int value.
	 * <p>
	 * Reads the next three bytes at this buffer's current position, composing
	 * them into an int value according to the current byte order, and then
	 * increments the position by three.
	 *
	 * @return The unsigned medium int value at the buffer's current position
	 */
	public abstract int getUnsignedMediumInt();

	/**
	 * Absolute <i>get</i> method for reading a medium int value.
	 * <p>
	 * Reads the next three bytes at this buffer's current position, composing
	 * them into an int value according to the current byte order.
	 *
	 * @param index The index from which the medium int will be read
	 * @return The medium int value at the given index
	 * @throws IndexOutOfBoundsException
	 *         If <tt>index</tt> is negative or not smaller than the buffer's limit
	 */
	public abstract int getMediumInt(int index);

	/**
	 * Absolute <i>get</i> method for reading an unsigned medium int value.
	 * <p>
	 * Reads the next three bytes at this buffer's current position, composing
	 * them into an int value according to the current byte order.
	 *
	 * @param index The index from which the unsigned medium int will be read
	 * @return The unsigned medium int value at the given index
	 * @throws IndexOutOfBoundsException
	 *         If <tt>index</tt> is negative or not smaller than the buffer's limit
	 */
	public abstract int getUnsignedMediumInt(int index);

	/**
	 * Relative <i>put</i> method for writing a medium int value.
	 * <p>
	 * Writes three bytes containing the given int value, in the current byte
	 * order, into this buffer at the current position, and then increments the
	 * position by three.
	 *
	 * @param value The medium int value to be written
	 * @return the modified IoBuffer
	 * @throws BufferOverflowException If there are fewer than three bytes remaining in this buffer
	 */
	public abstract IoBuffer putMediumInt(int value);

	/**
	 * Absolute <i>put</i> method for writing a medium int value.
	 * <p>
	 * Writes three bytes containing the given int value, in the current byte
	 * order, into this buffer at the given index.
	 *
	 * @param index The index at which the bytes will be written
	 * @param value The medium int value to be written
	 * @return the modified IoBuffer
	 * @throws IndexOutOfBoundsException
	 *         If <tt>index</tt> is negative or not smaller than the buffer's limit, minus three
	 */
	public abstract IoBuffer putMediumInt(int index, int value);

	/**
	 * @see ByteBuffer#putInt(int)
	 *
	 * @param value The int to put at the current position
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putInt(int value);

	/**
	 * Writes an unsigned int into the ByteBuffer
	 *
	 * @param value the byte to write
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putUnsignedInt(byte value);

	/**
	 * Writes an unsigned int into the ByteBuffer at a specified position
	 *
	 * @param index the position in the buffer to write the value
	 * @param value the byte to write
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putUnsignedInt(int index, byte value);

	/**
	 * Writes an unsigned int into the ByteBuffer
	 *
	 * @param value the short to write
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putUnsignedInt(short value);

	/**
	 * Writes an unsigned int into the ByteBuffer at a specified position
	 *
	 * @param index the position in the buffer to write the value
	 * @param value the short to write
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putUnsignedInt(int index, short value);

	/**
	 * Writes an unsigned short into the ByteBuffer
	 *
	 * @param value the byte to write
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putUnsignedShort(byte value);

	/**
	 * Writes an unsigned Short into the ByteBuffer at a specified position
	 *
	 * @param index the position in the buffer to write the value
	 * @param value the byte to write
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putUnsignedShort(int index, byte value);

	/**
	 * @see ByteBuffer#getInt(int)
	 *
	 * @param index The index in the IoBuffer where we will read an int from
	 * @return the int at the given position
	 */
	public abstract int getInt(int index);

	/**
	 * Reads four bytes unsigned integer.
	 *
	 * @param index The index in the IoBuffer where we will read an unsigned int from
	 * @return The long at the given position
	 */
	public abstract long getUnsignedInt(int index);

	/**
	 * @see ByteBuffer#putInt(int, int)
	 *
	 * @param index The position where to put the int
	 * @param value The int to put in the IoBuffer
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putInt(int index, int value);

	/**
	 * @see ByteBuffer#getLong()
	 *
	 * @return The long at the current position
	 */
	public abstract long getLong();

	/**
	 * @see ByteBuffer#putLong(int, long)
	 *
	 * @param value The log to put in the IoBuffer
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putLong(long value);

	/**
	 * @see ByteBuffer#getLong(int)
	 *
	 * @param index The index in the IoBuffer where we will read a long from
	 * @return the long at the given position
	 */
	public abstract long getLong(int index);

	/**
	 * @see ByteBuffer#putLong(int, long)
	 *
	 * @param index The position where to put the long
	 * @param value The long to put in the IoBuffer
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putLong(int index, long value);

	/**
	 * @see ByteBuffer#getFloat()
	 *
	 * @return the float at the current position
	 */
	public abstract float getFloat();

	/**
	 * @see ByteBuffer#putFloat(float)
	 *
	 * @param value The float to put in the IoBuffer
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putFloat(float value);

	/**
	 * @see ByteBuffer#getFloat(int)
	 *
	 * @param index The index in the IoBuffer where we will read a float from
	 * @return The float at the given position
	 */
	public abstract float getFloat(int index);

	/**
	 * @see ByteBuffer#putFloat(int, float)
	 *
	 * @param index The position where to put the float
	 * @param value The float to put in the IoBuffer
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putFloat(int index, float value);

	/**
	 * @see ByteBuffer#getDouble()
	 *
	 * @return the double at the current position
	 */
	public abstract double getDouble();

	/**
	 * @see ByteBuffer#putDouble(double)
	 *
	 * @param value The double to put at the IoBuffer current position
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putDouble(double value);

	/**
	 * @see ByteBuffer#getDouble(int)
	 *
	 * @param index The position where to get the double from
	 * @return The double at the given position
	 */
	public abstract double getDouble(int index);

	/**
	 * @see ByteBuffer#putDouble(int, double)
	 *
	 * @param index The position where to put the double
	 * @param value The double to put in the IoBuffer
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer putDouble(int index, double value);

	/**
	 * @return an {@link InputStream} that reads the data from this buffer.
	 * {@link InputStream#read()} returns <tt>-1</tt> if the buffer position reaches to the limit.
	 */
	public abstract InputStream asInputStream();

	/**
	 * @return an {@link OutputStream} that appends the data into this buffer.
	 * Please note that the {@link OutputStream#write(int)} will throw a
	 * {@link BufferOverflowException} instead of an {@link IOException} in case
	 * of buffer overflow. Please set <tt>autoExpand</tt> property by calling
	 * {@link #setAutoExpand(boolean)} to prevent the unexpected runtime exception.
	 */
	public abstract OutputStream asOutputStream();

	/**
	 * Returns hexdump of this buffer.
	 * The data and pointer are not changed as a result of this method call.
	 *
	 * @return hexidecimal representation of this buffer
	 */
	public abstract String getHexDump();

	/**
	 * Return hexdump of this buffer with limited length.
	 *
	 * @param lengthLimit The maximum number of bytes to dump from the current buffer position.
	 * @return hexidecimal representation of this buffer
	 */
	public abstract String getHexDump(int lengthLimit);

	////////////////////////////////
	// String getters and putters //
	////////////////////////////////

	/**
	 * Reads a <code>NUL</code>-terminated string from this buffer using the
	 * specified <code>decoder</code> and returns it. This method reads until
	 * the limit of this buffer if no <tt>NUL</tt> is found.
	 *
	 * @param decoder The {@link CharsetDecoder} to use
	 * @return the read String
	 * @exception CharacterCodingException Thrown when an error occurred while decoding the buffer
	 */
	public abstract String getString(CharsetDecoder decoder) throws CharacterCodingException;

	/**
	 * Reads a <code>NUL</code>-terminated string from this buffer using the
	 * specified <code>decoder</code> and returns it.
	 *
	 * @param fieldSize the maximum number of bytes to read
	 * @param decoder The {@link CharsetDecoder} to use
	 * @return the read String
	 * @exception CharacterCodingException Thrown when an error occurred while decoding the buffer
	 */
	public abstract String getString(int fieldSize, CharsetDecoder decoder) throws CharacterCodingException;

	/**
	 * Writes the content of <code>in</code> into this buffer using the
	 * specified <code>encoder</code>. This method doesn't terminate string with
	 * <tt>NUL</tt>. You have to do it by yourself.
	 *
	 * @param val The CharSequence to put in the IoBuffer
	 * @param encoder The CharsetEncoder to use
	 * @return The modified IoBuffer
	 * @throws CharacterCodingException When we have an error while decoding the String
	 */
	public abstract IoBuffer putString(CharSequence val, CharsetEncoder encoder) throws CharacterCodingException;

	/**
	 * Writes the content of <code>in</code> into this buffer as a
	 * <code>NUL</code>-terminated string using the specified <code>encoder</code>.
	 * <p>
	 * If the charset name of the encoder is UTF-16, you cannot specify odd <code>fieldSize</code>,
	 * and this method will append two <code>NUL</code>s as a terminator.
	 * <p>
	 * Please note that this method doesn't terminate with <code>NUL</code> if
	 * the input string is longer than <tt>fieldSize</tt>.
	 *
	 * @param val The CharSequence to put in the IoBuffer
	 * @param fieldSize the maximum number of bytes to write
	 * @param encoder The CharsetEncoder to use
	 * @return The modified IoBuffer
	 * @throws CharacterCodingException When we have an error while decoding the String
	 */
	public abstract IoBuffer putString(CharSequence val, int fieldSize, CharsetEncoder encoder)
			throws CharacterCodingException;

	/////////////////////
	// IndexOf methods //
	/////////////////////

	/**
	 * Returns the first occurrence position of the specified byte from the
	 * current position to the current limit.
	 *
	 * @param b The byte we are looking for
	 * @return <tt>-1</tt> if the specified byte is not found
	 */
	public abstract int indexOf(byte b);

	//////////////////////////
	// Skip or fill methods //
	//////////////////////////

	/**
	 * Forwards the position of this buffer as the specified <code>size</code> bytes.
	 *
	 * @param size The added size
	 * @return The modified IoBuffer
	 */
	public abstract IoBuffer skip(int size);

	/**
	 * Fills this buffer with the specified value. This method moves buffer position forward.
	 *
	 * @param value The value to fill the IoBuffer with
	 * @param size The added size
	 * @return The modified IoBuffer
	 */
	public abstract IoBuffer fill(byte value, int size);

	/**
	 * Fills this buffer with the specified value. This method does not change buffer position.
	 *
	 * @param value The value to fill the IoBuffer with
	 * @param size The added size
	 * @return The modified IoBuffer
	 */
	public abstract IoBuffer fillAndReset(byte value, int size);

	/**
	 * Fills this buffer with <code>NUL (0x00)</code>. This method moves buffer position forward.
	 *
	 * @param size The added size
	 * @return The modified IoBuffer
	 */
	public abstract IoBuffer fill(int size);

	/**
	 * Fills this buffer with <code>NUL (0x00)</code>. This method does not change buffer position.
	 *
	 * @param size The added size
	 * @return The modified IoBuffer
	 */
	public abstract IoBuffer fillAndReset(int size);
}
