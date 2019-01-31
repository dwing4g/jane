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

import java.nio.ByteBuffer;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.write.DefaultWriteRequest;
import org.apache.mina.core.write.WriteRequest;

/**
 * A byte buffer used by MINA applications.
 * <p>
 * This is a replacement for {@link ByteBuffer}.
 * Please refer to {@link ByteBuffer} documentation for preliminary usage.
 * MINA does not use NIO {@link ByteBuffer} directly for two reasons:
 * <ul>
 *   <li>It doesn't provide useful getters and putters such as <code>fill</code> enough.</li>
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
 *   This class provides a few <tt>wrap(...)</tt> methods that wraps any NIO buffers and byte arrays.
 *
 * <h2>Changing Buffer Allocation Policy</h2>
 * <p>
 *   The {@link IoBufferAllocator} interface lets you override the default buffer management behavior.
 *   There is only one allocator provided out-of-the-box:
 *   <ul>
 *     <li>{@link SimpleBufferAllocator} (default)</li>
 *   </ul>
 *   You can implement your own allocator and use it by calling {@link #setAllocator(IoBufferAllocator)}.
 */
public abstract class IoBuffer implements Comparable<IoBuffer>, WriteRequest {
	/** The allocator used to create new buffers */
	private static IoBufferAllocator allocator = SimpleBufferAllocator.instance;

	/** A flag indicating which type of buffer we are using: heap or direct */
	private static boolean useDirectBuffer;

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
		if (newAllocator == null)
			throw new IllegalArgumentException("allocator");

		IoBufferAllocator oldAllocator = allocator;
		allocator = newAllocator;
		oldAllocator.dispose();
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
		if (capacity < 0)
			throw new IllegalArgumentException("capacity: " + capacity);

		return allocator.allocate(capacity, isDirectBuffer);
	}

	public static IoBuffer reallocate(IoBuffer buf, int capacity) {
		if (buf.capacity() >= capacity)
			return buf;

		int limit = buf.limit();
		int pos = buf.position();

		IoBuffer newBuf = allocate(capacity, buf.isDirect());
		buf.clear();
		newBuf.put(buf);
		newBuf.limit(limit);
		newBuf.position(pos);
		newBuf.buf().order(buf.buf().order());
		buf.free();

		return newBuf;
	}

	public static IoBuffer reallocateNew(IoBuffer buf, int capacity) {
		return reallocate(buf, capacity).clear();
	}

	public static IoBuffer reallocateRemain(IoBuffer buf, int remainCapacity) {
		buf = reallocate(buf, buf.position() + remainCapacity);
		return buf.limit(buf.capacity());
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

	@Override
	public final Object writeRequestMessage() {
		return this;
	}

	@Override
	public final WriteFuture writeRequestFuture() {
		return DefaultWriteRequest.UNUSED_FUTURE;
	}

	/**
	 * @return the underlying NIO {@link ByteBuffer} instance.
	 */
	public abstract ByteBuffer buf();

	/**
	 * @see ByteBuffer#duplicate()
	 *
	 * @return the modified IoBuffer
	 */
	public abstract IoBuffer duplicate();

	/**
	 * Declares this buffer and all its derived buffers are not used anymore
	 * so that it can be reused by some {@link IoBufferAllocator} implementations.
	 * It is not mandatory to call this method,
	 * but you might want to invoke this method for maximum performance.
	 */
	public abstract void free();

	/**
	 * @see ByteBuffer#isDirect()
	 *
	 * @return <tt>true</tt> if this is a direct buffer
	 */
	public final boolean isDirect() {
		return buf().isDirect();
	}

	/**
	 * @see ByteBuffer#capacity()
	 *
	 * @return the buffer capacity
	 */
	public final int capacity() {
		return buf().capacity();
	}

	/**
	 * @see java.nio.Buffer#position()
	 *
	 * @return The current position in the buffer
	 */
	public final int position() {
		return buf().position();
	}

	/**
	 * @see java.nio.Buffer#position(int)
	 *
	 * @param newPosition Sets the new position in the buffer
	 * @return the modified IoBuffer
	 */
	public final IoBuffer position(int newPosition) {
		buf().position(newPosition);
		return this;
	}

	/**
	 * @see java.nio.Buffer#limit()
	 *
	 * @return the modified IoBuffer's limit
	 */
	public final int limit() {
		return buf().limit();
	}

	/**
	 * @see java.nio.Buffer#limit(int)
	 *
	 * @param newLimit The new buffer's limit
	 * @return the modified IoBuffer
	 */
	public final IoBuffer limit(int newLimit) {
		buf().limit(newLimit);
		return this;
	}

	/**
	 * @see java.nio.Buffer#clear()
	 *
	 * @return the modified IoBuffer
	 */
	public final IoBuffer clear() {
		buf().clear();
		return this;
	}

	/**
	 * Clears this buffer and fills its content with <tt>NUL</tt>.
	 * The position is set to zero, the limit is set to the capacity.
	 *
	 * @return the modified IoBuffer
	 */
	public final IoBuffer sweep() {
		return clear().fillAndReset(remaining());
	}

	/**
	 * double Clears this buffer and fills its content with <tt>value</tt>.
	 * The position is set to zero, the limit is set to the capacity.
	 *
	 * @param value The value to put in the buffer
	 * @return the modified IoBuffer
	 */
	public final IoBuffer sweep(byte value) {
		return clear().fillAndReset(value, remaining());
	}

	/**
	 * @see java.nio.Buffer#flip()
	 *
	 * @return the modified IoBuffer
	 */
	public final IoBuffer flip() {
		buf().flip();
		return this;
	}

	/**
	 * @see java.nio.Buffer#rewind()
	 *
	 * @return the modified IoBuffer
	 */
	public final IoBuffer rewind() {
		buf().rewind();
		return this;
	}

	/**
	 * @see java.nio.Buffer#remaining()
	 *
	 * @return The remaining bytes in the buffer
	 */
	public final int remaining() {
		return buf().remaining();
	}

	/**
	 * @see java.nio.Buffer#hasRemaining()
	 *
	 * @return <tt>true</tt> if there are some remaining bytes in the buffer
	 */
	public final boolean hasRemaining() {
		return buf().hasRemaining();
	}

	/**
	 * @see ByteBuffer#hasArray()
	 *
	 * @return <tt>true</tt> if the {@link #array()} method will return a byte[]
	 */
	public final boolean hasArray() {
		return buf().hasArray();
	}

	/**
	 * @see ByteBuffer#array()
	 *
	 * @return A byte[] if this IoBuffer supports it
	 */
	public final byte[] array() {
		return buf().array();
	}

	/**
	 * @see ByteBuffer#arrayOffset()
	 *
	 * @return The offset in the returned byte[] when the {@link #array()} method is called
	 */
	public final int arrayOffset() {
		return buf().arrayOffset();
	}

	/**
	 * @see ByteBuffer#get()
	 *
	 * @return The byte at the current position
	 */
	public final byte get() {
		return buf().get();
	}

	/**
	 * Reads one unsigned byte as a integer.
	 *
	 * @return the unsigned byte at the current position
	 */
	public final int getUnsignedByte() {
		return get() & 0xff;
	}

	/**
	 * @see ByteBuffer#put(byte)
	 *
	 * @param b The byte to put in the buffer
	 * @return the modified IoBuffer
	 */
	public final IoBuffer put(byte b) {
		buf().put(b);
		return this;
	}

	/**
	 * @see ByteBuffer#get(int)
	 *
	 * @param index The position for which we want to read a byte
	 * @return the byte at the given position
	 */
	public final byte get(int index) {
		return buf().get(index);
	}

	/**
	 * Reads one byte as an unsigned integer.
	 *
	 * @param index The position for which we want to read an unsigned byte
	 * @return the unsigned byte at the given position
	 */
	public final int getUnsigned(int index) {
		return get(index) & 0xff;
	}

	/**
	 * @see ByteBuffer#put(int, byte)
	 *
	 * @param index The position where the byte will be put
	 * @param b The byte to put
	 * @return the modified IoBuffer
	 */
	public final IoBuffer put(int index, byte b) {
		buf().put(index, b);
		return this;
	}

	/**
	 * @see ByteBuffer#get(byte[], int, int)
	 *
	 * @param dst The destination buffer
	 * @param offset The position in the original buffer
	 * @param length The number of bytes to copy
	 * @return the modified IoBuffer
	 */
	public final IoBuffer get(byte[] dst, int offset, int length) {
		buf().get(dst, offset, length);
		return this;
	}

	/**
	 * @see ByteBuffer#get(byte[])
	 *
	 * @param dst The byte[] that will contain the read bytes
	 * @return the IoBuffer
	 */
	public final IoBuffer get(byte[] dst) {
		return get(dst, 0, dst.length);
	}

	/**
	 * Writes the content of the specified <tt>src</tt> into this buffer.
	 *
	 * @param src The source ByteBuffer
	 * @return the modified IoBuffer
	 */
	public final IoBuffer put(ByteBuffer src) {
		buf().put(src);
		return this;
	}

	/**
	 * Writes the content of the specified <tt>src</tt> into this buffer.
	 *
	 * @param src The source IoBuffer
	 * @return the modified IoBuffer
	 */
	public final IoBuffer put(IoBuffer src) {
		return put(src.buf());
	}

	/**
	 * @see ByteBuffer#put(byte[], int, int)
	 *
	 * @param src The byte[] to put
	 * @param offset The position in the source
	 * @param length The number of bytes to copy
	 * @return the modified IoBuffer
	 */
	public final IoBuffer put(byte[] src, int offset, int length) {
		buf().put(src, offset, length);
		return this;
	}

	/**
	 * @see ByteBuffer#put(byte[])
	 *
	 * @param src The byte[] to put
	 * @return the modified IoBuffer
	 */
	public final IoBuffer put(byte[] src) {
		return put(src, 0, src.length);
	}

	/**
	 * @see ByteBuffer#compact()
	 *
	 * @return the modified IoBuffer
	 */
	public final IoBuffer compact() {
		buf().compact();
		return this;
	}

	/**
	 * Returns hexdump of this buffer.
	 * The data and pointer are not changed as a result of this method call.
	 *
	 * @return hexidecimal representation of this buffer
	 */
	public final String getHexDump() {
		return getHexDump(Integer.MAX_VALUE);
	}

	/** The getHexdump digits lookup table */
	private static final byte[] digits = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };

	/**
	 * Return hexdump of this buffer with limited length.
	 *
	 * @param lengthLimit The maximum number of bytes to dump from the current buffer position.
	 * @return hexidecimal representation of this buffer
	 */
	public final String getHexDump(int lengthLimit) {
		if (lengthLimit <= 0)
			throw new IllegalArgumentException("lengthLimit: " + lengthLimit + " (expected: 1+)");

		int limit = limit();
		int pos = position();
		int len = limit - pos;
		if (len <= 0)
			return "empty";
		boolean truncate = len > lengthLimit;
		if (truncate)
			limit = pos + (len = lengthLimit);

		StringBuilder out = new StringBuilder(len * 3 + 2);

		for (;;) {
			int byteValue = get(pos) & 0xff;
			out.append((char)digits[byteValue >> 4]);
			out.append((char)digits[byteValue & 15]);
			if (++pos >= limit)
				break;
			out.append(' ');
		}

		if (truncate)
			out.append("...");

		return out.toString();
	}

	/////////////////////
	// IndexOf methods //
	/////////////////////

	/**
	 * Returns the first occurrence position of the specified byte from the current position to the current limit.
	 *
	 * @param b The byte we are looking for
	 * @return <tt>-1</tt> if the specified byte is not found
	 */
	public final int indexOf(byte b) {
		if (hasArray()) {
			byte[] array = array();
			int arrayOffset = arrayOffset();
			for (int i = arrayOffset + position(), limit = arrayOffset + limit(); i < limit; i++) {
				if (array[i] == b)
					return i - arrayOffset;
			}
		} else {
			for (int i = position(), limit = limit(); i < limit; i++) {
				if (get(i) == b)
					return i;
			}
		}
		return -1;
	}

	//////////////////////////
	// Skip or fill methods //
	//////////////////////////

	/**
	 * Forwards the position of this buffer as the specified <code>size</code> bytes.
	 *
	 * @param size The added size
	 * @return The modified IoBuffer
	 */
	public final IoBuffer skip(int size) {
		return position(position() + size);
	}

	/**
	 * Fills this buffer with the specified value. This method moves buffer position forward.
	 *
	 * @param value The value to fill the IoBuffer with
	 * @param size The added size
	 * @return The modified IoBuffer
	 */
	public final IoBuffer fill(byte value, int size) {
		ByteBuffer bb = buf();

		if (size >= 8) {
			long longValue = value & 0xffL;
			longValue |= longValue << 8;
			longValue |= longValue << 16;
			longValue |= longValue << 32;
			do
				bb.putLong(longValue);
			while ((size -= 8) >= 8);
		}

		if (size >= 4) {
			size -= 4;
			int intValue = value & 0xff;
			intValue |= intValue << 8;
			intValue |= intValue << 16;
			bb.putInt(intValue);
		}

		if (size >= 2) {
			size -= 2;
			bb.putShort((short)(value & 0xff | (value << 8)));
		}

		if (size > 0)
			bb.put(value);

		return this;
	}

	/**
	 * Fills this buffer with the specified value. This method does not change buffer position.
	 *
	 * @param value The value to fill the IoBuffer with
	 * @param size The added size
	 * @return The modified IoBuffer
	 */
	public final IoBuffer fillAndReset(byte value, int size) {
		int pos = position();
		try {
			fill(value, size);
		} finally {
			position(pos);
		}
		return this;
	}

	/**
	 * Fills this buffer with <code>NUL (0x00)</code>. This method moves buffer position forward.
	 *
	 * @param size The added size
	 * @return The modified IoBuffer
	 */
	public final IoBuffer fill(int size) {
		ByteBuffer bb = buf();

		for (; size >= 8; size -= 8)
			bb.putLong(0L);

		if (size >= 4) {
			size -= 4;
			bb.putInt(0);
		}

		if (size >= 2) {
			size -= 2;
			bb.putShort((short)0);
		}

		if (size > 0)
			bb.put((byte)0);

		return this;
	}

	/**
	 * Fills this buffer with <code>NUL (0x00)</code>. This method does not change buffer position.
	 *
	 * @param size The added size
	 * @return The modified IoBuffer
	 */
	public final IoBuffer fillAndReset(int size) {
		int pos = position();
		try {
			fill(size);
		} finally {
			position(pos);
		}
		return this;
	}

	@Override
	public int hashCode() {
		int h = remaining();
		for (int i = position(), n = limit(); i < n; i++)
			h = h * 16777619 + get(i);
		return h;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof IoBuffer))
			return false;

		IoBuffer that = (IoBuffer)o;
		if (this.remaining() != that.remaining())
			return false;

		for (int i = this.limit() - 1, j = that.limit() - 1, p = this.position(); i >= p; i--, j--) {
			if (this.get(i) != that.get(j))
				return false;
		}
		return true;
	}

	@Override
	public int compareTo(IoBuffer that) {
		int r0 = this.remaining(), r1 = that.remaining();
		for (int i = this.position(), j = that.position(), n = i + Math.min(r0, r1); i < n; i++, j++) {
			int d = this.get(i) - that.get(j);
			if (d != 0)
				return d;
		}
		return r0 - r1;
	}

	@Override
	public String toString() {
		return new StringBuilder(64).append(isDirect() ? "DirBuf[" : "HeapBuf[")
				.append(position()).append('/').append(limit()).append('/').append(capacity())
				.append(": ").append(getHexDump(16)).append(']').toString();
	}
}
