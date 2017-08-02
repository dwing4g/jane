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
package org.apache.mina.core.buffer;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;

/**
 * A base implementation of {@link IoBuffer}.  This implementation
 * assumes that {@link IoBuffer#buf()} always returns a correct NIO
 * {@link ByteBuffer} instance.  Most implementations could
 * extend this class and implement their own buffer management mechanism.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 * @see IoBufferAllocator
 */
public abstract class AbstractIoBuffer extends IoBuffer {
	/** Tells if a buffer has been created from an existing buffer */
	private final boolean derived;

	/** A flag set to true if the buffer can extend automatically */
	private boolean autoExpand;

	/** A flag set to true if the buffer can shrink automatically */
	private boolean autoShrink;

	/** Tells if a buffer can be expanded */
	private boolean recapacityAllowed;

	/** The minimum number of bytes the IoBuffer can hold */
	private int minimumCapacity;

	private static final Field markField;

	static {
		try {
			markField = Buffer.class.getDeclaredField("mark");
			markField.setAccessible(true);
		} catch(Exception e) {
			throw new Error(e);
		}
	}

	/**
	 * Creates a new parent buffer.
	 *
	 * @param initialCapacity The initial buffer capacity when created
	 */
	protected AbstractIoBuffer(int initialCapacity) {
		recapacityAllowed = true;
		derived = false;
		minimumCapacity = initialCapacity;
	}

	/**
	 * Creates a new derived buffer. A derived buffer uses an existing
	 * buffer properties - capacity -.
	 *
	 * @param parent The buffer we get the properties from
	 */
	protected AbstractIoBuffer(AbstractIoBuffer parent) {
		recapacityAllowed = false;
		derived = true;
		minimumCapacity = parent.minimumCapacity;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final boolean isDirect() {
		return buf().isDirect();
	}

	/**
	 * Sets the underlying NIO buffer instance.
	 *
	 * @param newBuf The buffer to store within this IoBuffer
	 */
	protected abstract void buf(ByteBuffer newBuf);

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final int minimumCapacity() {
		return minimumCapacity;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer minimumCapacity(int capacity) {
		if (capacity < 0) {
			throw new IllegalArgumentException("capacity: " + capacity);
		}
		minimumCapacity = capacity;
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final int capacity() {
		return buf().capacity();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer capacity(int newCapacity) {
		if (!recapacityAllowed) {
			throw new IllegalStateException("Derived buffers and their parent can't be expanded.");
		}

		// Allocate a new buffer and transfer all settings to it.
		if (newCapacity > capacity()) {
			// Expand:
			//// Save the state.
			int pos = position();
			int limit = limit();
			int mark = markValue();
			ByteOrder bo = order();

			//// Reallocate.
			ByteBuffer oldBuf = buf();
			ByteBuffer newBuf = getAllocator().allocateNioBuffer(newCapacity, isDirect());
			oldBuf.clear();
			newBuf.put(oldBuf);
			buf(newBuf);

			//// Restore the state.
			buf().limit(limit);
			if (mark >= 0) {
				buf().position(mark);
				buf().mark();
			}
			buf().position(pos);
			buf().order(bo);
		}

		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final boolean isAutoExpand() {
		return autoExpand && recapacityAllowed;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final boolean isAutoShrink() {
		return autoShrink && recapacityAllowed;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final boolean isDerived() {
		return derived;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer setAutoExpand(boolean autoExpand) {
		if (!recapacityAllowed) {
			throw new IllegalStateException("Derived buffers and their parent can't be expanded.");
		}
		this.autoExpand = autoExpand;
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer setAutoShrink(boolean autoShrink) {
		if (!recapacityAllowed) {
			throw new IllegalStateException("Derived buffers and their parent can't be shrinked.");
		}
		this.autoShrink = autoShrink;
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer expand(int expectedRemaining) {
		return expand(position(), expectedRemaining, false);
	}

	private IoBuffer expand(int expectedRemaining, boolean isAutoExpand) {
		return expand(position(), expectedRemaining, isAutoExpand);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer expand(int pos, int expectedRemaining) {
		return expand(pos, expectedRemaining, false);
	}

	private IoBuffer expand(int pos, int expectedRemaining, boolean isAutoExpand) {
		if (!recapacityAllowed) {
			throw new IllegalStateException("Derived buffers and their parent can't be expanded.");
		}

		int end = pos + expectedRemaining;
		int newCapacity = (isAutoExpand ? IoBuffer.normalizeCapacity(end) : end);
		if (newCapacity > capacity()) {
			// The buffer needs expansion.
			capacity(newCapacity);
		}

		if (end > limit()) {
			// We call limit() directly to prevent StackOverflowError
			buf().limit(end);
		}
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer shrink() {

		if (!recapacityAllowed) {
			throw new IllegalStateException("Derived buffers and their parent can't be expanded.");
		}

		int position = position();
		int capacity = capacity();
		int limit = limit();

		if (capacity == limit) {
			return this;
		}

		int newCapacity = capacity;
		int minCapacity = Math.max(minimumCapacity, limit);

		for (;;) {
			if (newCapacity >>> 1 < minCapacity) {
				break;
			}

			newCapacity >>>= 1;

			if (minCapacity == 0) {
				break;
			}
		}

		newCapacity = Math.max(minCapacity, newCapacity);

		if (newCapacity == capacity) {
			return this;
		}

		// Shrink and compact:
		//// Save the state.
		ByteOrder bo = order();

		//// Reallocate.
		ByteBuffer oldBuf = buf();
		ByteBuffer newBuf = getAllocator().allocateNioBuffer(newCapacity, isDirect());
		oldBuf.position(0);
		oldBuf.limit(limit);
		newBuf.put(oldBuf);
		buf(newBuf);

		//// Restore the state.
		buf().position(position);
		buf().limit(limit);
		buf().order(bo);

		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final int position() {
		return buf().position();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer position(int newPosition) {
		autoExpand(newPosition, 0);
		buf().position(newPosition);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final int limit() {
		return buf().limit();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer limit(int newLimit) {
		autoExpand(newLimit, 0);
		buf().limit(newLimit);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer mark() {
		ByteBuffer byteBuffer = buf();
		byteBuffer.mark();
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final int markValue() {
		try {
			return markField.getInt(buf());
		} catch(Exception e) {
			throw new Error(e);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer reset() {
		buf().reset();
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer clear() {
		buf().clear();
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer sweep() {
		clear();
		return fillAndReset(remaining());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer sweep(byte value) {
		clear();
		return fillAndReset(value, remaining());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer flip() {
		buf().flip();
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer rewind() {
		buf().rewind();
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final int remaining() {
		ByteBuffer byteBuffer = buf();
		return byteBuffer.limit() - byteBuffer.position();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final boolean hasRemaining() {
		ByteBuffer byteBuffer = buf();
		return byteBuffer.limit() > byteBuffer.position();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final byte get() {
		return buf().get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final int getUnsigned() {
		return get() & 0xff;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer put(byte b) {
		autoExpand(1);
		buf().put(b);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final byte get(int index) {
		return buf().get(index);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final int getUnsigned(int index) {
		return get(index) & 0xff;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer put(int index, byte b) {
		autoExpand(index, 1);
		buf().put(index, b);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer get(byte[] dst, int offset, int length) {
		buf().get(dst, offset, length);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer put(ByteBuffer src) {
		autoExpand(src.remaining());
		buf().put(src);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer put(byte[] src, int offset, int length) {
		autoExpand(length);
		buf().put(src, offset, length);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer compact() {
		int remaining = remaining();
		int capacity = capacity();

		if (capacity == 0) {
			return this;
		}

		if (isAutoShrink() && remaining <= capacity >>> 2 && capacity > minimumCapacity) {
			int newCapacity = capacity;
			int minCapacity = Math.max(minimumCapacity, remaining << 1);
			for (;;) {
				if (newCapacity >>> 1 < minCapacity) {
					break;
				}
				newCapacity >>>= 1;
			}

			newCapacity = Math.max(minCapacity, newCapacity);

			if (newCapacity == capacity) {
				return this;
			}

			// Shrink and compact:
			//// Save the state.
			ByteOrder bo = order();

			//// Sanity check.
			if (remaining > newCapacity) {
				throw new IllegalStateException("The amount of the remaining bytes is greater than "
						+ "the new capacity.");
			}

			//// Reallocate.
			ByteBuffer oldBuf = buf();
			ByteBuffer newBuf = getAllocator().allocateNioBuffer(newCapacity, isDirect());
			newBuf.put(oldBuf);
			buf(newBuf);

			//// Restore the state.
			buf().order(bo);
		} else {
			buf().compact();
		}
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final ByteOrder order() {
		return buf().order();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer order(ByteOrder bo) {
		buf().order(bo);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final char getChar() {
		return buf().getChar();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putChar(char value) {
		autoExpand(2);
		buf().putChar(value);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final char getChar(int index) {
		return buf().getChar(index);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putChar(int index, char value) {
		autoExpand(index, 2);
		buf().putChar(index, value);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final short getShort() {
		return buf().getShort();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putShort(short value) {
		autoExpand(2);
		buf().putShort(value);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final short getShort(int index) {
		return buf().getShort(index);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putShort(int index, short value) {
		autoExpand(index, 2);
		buf().putShort(index, value);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final int getInt() {
		return buf().getInt();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putInt(int value) {
		autoExpand(4);
		buf().putInt(value);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putUnsignedInt(byte value) {
		autoExpand(4);
		buf().putInt(value & 0x00ff);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putUnsignedInt(int index, byte value) {
		autoExpand(index, 4);
		buf().putInt(index, value & 0x00ff);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putUnsignedInt(short value) {
		autoExpand(4);
		buf().putInt(value & 0x0000ffff);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putUnsignedInt(int index, short value) {
		autoExpand(index, 4);
		buf().putInt(index, value & 0x0000ffff);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putUnsignedShort(byte value) {
		autoExpand(2);
		buf().putShort((short) (value & 0x00ff));
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putUnsignedShort(int index, byte value) {
		autoExpand(index, 2);
		buf().putShort(index, (short) (value & 0x00ff));
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final int getInt(int index) {
		return buf().getInt(index);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putInt(int index, int value) {
		autoExpand(index, 4);
		buf().putInt(index, value);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final long getLong() {
		return buf().getLong();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putLong(long value) {
		autoExpand(8);
		buf().putLong(value);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final long getLong(int index) {
		return buf().getLong(index);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putLong(int index, long value) {
		autoExpand(index, 8);
		buf().putLong(index, value);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final float getFloat() {
		return buf().getFloat();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putFloat(float value) {
		autoExpand(4);
		buf().putFloat(value);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final float getFloat(int index) {
		return buf().getFloat(index);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putFloat(int index, float value) {
		autoExpand(index, 4);
		buf().putFloat(index, value);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final double getDouble() {
		return buf().getDouble();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putDouble(double value) {
		autoExpand(8);
		buf().putDouble(value);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final double getDouble(int index) {
		return buf().getDouble(index);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer putDouble(int index, double value) {
		autoExpand(index, 8);
		buf().putDouble(index, value);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer duplicate() {
		recapacityAllowed = false;
		return duplicate0();
	}

	/**
	 * Implement this method to return the unexpandable duplicate of this
	 * buffer.
	 *
	 * @return the IoBoffer instance
	 */
	protected abstract IoBuffer duplicate0();

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer slice() {
		recapacityAllowed = false;
		return slice0();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer getSlice(int index, int length) {
		if (length < 0) {
			throw new IllegalArgumentException("length: " + length);
		}

		int pos = position();
		int limit = limit();

		if (index > limit) {
			throw new IllegalArgumentException("index: " + index);
		}

		int endIndex = index + length;

		if (endIndex > limit) {
			throw new IndexOutOfBoundsException("index + length (" + endIndex + ") is greater than limit ("
					+ limit + ')');
		}

		clear();
		limit(endIndex);
		position(index);

		IoBuffer slice = slice();
		limit(limit);
		position(pos);

		return slice;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final IoBuffer getSlice(int length) {
		if (length < 0) {
			throw new IllegalArgumentException("length: " + length);
		}
		int pos = position();
		int limit = limit();
		int nextPos = pos + length;
		if (limit < nextPos) {
			throw new IndexOutOfBoundsException("position + length (" + nextPos + ") is greater than limit ("
					+ limit + ')');
		}

		limit(pos + length);
		IoBuffer slice = slice();
		position(nextPos);
		limit(limit);
		return slice;
	}

	/**
	 * Implement this method to return the unexpandable slice of this
	 * buffer.
	 *
	 * @return the IoBoffer instance
	 */
	protected abstract IoBuffer slice0();

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int hashCode() {
		int h = 1;
		int p = position();
		for (int i = limit() - 1; i >= p; i--) {
			h = 31 * h + get(i);
		}
		return h;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean equals(Object o) {
		if (!(o instanceof IoBuffer)) {
			return false;
		}

		IoBuffer that = (IoBuffer) o;
		if (this.remaining() != that.remaining()) {
			return false;
		}

		int p = this.position();
		for (int i = this.limit() - 1, j = that.limit() - 1; i >= p; i--, j--) {
			byte v1 = this.get(i);
			byte v2 = that.get(j);
			if (v1 != v2) {
				return false;
			}
		}
		return true;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int compareTo(IoBuffer that) {
		int n = this.position() + Math.min(this.remaining(), that.remaining());
		for (int i = this.position(), j = that.position(); i < n; i++, j++) {
			byte v1 = this.get(i);
			byte v2 = that.get(j);
			if (v1 == v2) {
				continue;
			}
			if (v1 < v2) {
				return -1;
			}

			return +1;
		}
		return this.remaining() - that.remaining();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder();
		buf.append(isDirect() ? "Direct" : "Heap");
		buf.append("Buffer[pos=").append(position());
		buf.append(" lim=").append(limit());
		buf.append(" cap=").append(capacity());
		buf.append(": ").append(getHexDump(16)).append(']');
		return buf.toString();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IoBuffer get(byte[] dst) {
		return get(dst, 0, dst.length);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IoBuffer put(IoBuffer src) {
		return put(src.buf());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IoBuffer put(byte[] src) {
		return put(src, 0, src.length);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int getUnsignedShort() {
		return getShort() & 0xffff;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int getUnsignedShort(int index) {
		return getShort(index) & 0xffff;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public long getUnsignedInt() {
		return getInt() & 0xffffffffL;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int getMediumInt() {
		byte b1 = get();
		byte b2 = get();
		byte b3 = get();
		return ByteOrder.BIG_ENDIAN.equals(order()) ?
			getMediumInt(b1, b2, b3) :
			getMediumInt(b3, b2, b1);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int getUnsignedMediumInt() {
		int b1 = getUnsigned();
		int b2 = getUnsigned();
		int b3 = getUnsigned();
		return ByteOrder.BIG_ENDIAN.equals(order()) ?
			b1 << 16 | b2 << 8 | b3 :
			b3 << 16 | b2 << 8 | b1;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int getMediumInt(int index) {
		byte b1 = get(index);
		byte b2 = get(index + 1);
		byte b3 = get(index + 2);
		return ByteOrder.BIG_ENDIAN.equals(order()) ?
			getMediumInt(b1, b2, b3) :
			getMediumInt(b3, b2, b1);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int getUnsignedMediumInt(int index) {
		int b1 = getUnsigned(index);
		int b2 = getUnsigned(index + 1);
		int b3 = getUnsigned(index + 2);
		return ByteOrder.BIG_ENDIAN.equals(order()) ?
			b1 << 16 | b2 << 8 | b3 :
			b3 << 16 | b2 << 8 | b1;
	}

	private static int getMediumInt(byte b1, byte b2, byte b3) {
		int ret = b1 << 16 & 0xff0000 | b2 << 8 & 0xff00 | b3 & 0xff;
		// Check to see if the medium int is negative (high bit in b1 set)
		if ((b1 & 0x80) == 0x80) {
			// Make the the whole int negative
			ret |= 0xff000000;
		}
		return ret;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IoBuffer putMediumInt(int value) {
		byte b1 = (byte) (value >> 16);
		byte b2 = (byte) (value >> 8);
		byte b3 = (byte) value;

		if (ByteOrder.BIG_ENDIAN.equals(order())) {
			put(b1).put(b2).put(b3);
		} else {
			put(b3).put(b2).put(b1);
		}

		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IoBuffer putMediumInt(int index, int value) {
		byte b1 = (byte) (value >> 16);
		byte b2 = (byte) (value >> 8);
		byte b3 = (byte) value;

		if (ByteOrder.BIG_ENDIAN.equals(order())) {
			put(index, b1).put(index + 1, b2).put(index + 2, b3);
		} else {
			put(index, b3).put(index + 1, b2).put(index + 2, b1);
		}

		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public long getUnsignedInt(int index) {
		return getInt(index) & 0xffffffffL;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public InputStream asInputStream() {
		return new InputStream() {
			@Override
			public int available() {
				return AbstractIoBuffer.this.remaining();
			}

			@Override
			public synchronized void mark(int readlimit) {
				AbstractIoBuffer.this.mark();
			}

			@Override
			public boolean markSupported() {
				return true;
			}

			@Override
			public int read() {
				if (AbstractIoBuffer.this.hasRemaining()) {
					return AbstractIoBuffer.this.get() & 0xff;
				}

				return -1;
			}

			@Override
			public int read(byte[] b, int off, int len) {
				int remaining = AbstractIoBuffer.this.remaining();
				if (remaining > 0) {
					int readBytes = Math.min(remaining, len);
					AbstractIoBuffer.this.get(b, off, readBytes);
					return readBytes;
				}

				return -1;
			}

			@Override
			public synchronized void reset() {
				AbstractIoBuffer.this.reset();
			}

			@Override
			public long skip(long n) {
				int bytes = AbstractIoBuffer.this.remaining();
				if (n <= Integer.MAX_VALUE) {
					bytes = Math.min(bytes, (int) n);
				}
				AbstractIoBuffer.this.skip(bytes);
				return bytes;
			}
		};
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public OutputStream asOutputStream() {
		return new OutputStream() {
			@Override
			public void write(byte[] b, int off, int len) {
				AbstractIoBuffer.this.put(b, off, len);
			}

			@Override
			public void write(int b) {
				AbstractIoBuffer.this.put((byte) b);
			}
		};
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getHexDump() {
		return this.getHexDump(Integer.MAX_VALUE);
	}

	/** The getHexdump digits lookup table */
	private static final byte[] digits = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getHexDump(int lengthLimit) {
		if (lengthLimit == 0) {
			throw new IllegalArgumentException("lengthLimit: " + lengthLimit + " (expected: 1+)");
		}

		boolean truncate = (remaining() > lengthLimit);
		int size = (truncate ? lengthLimit : remaining());
		if (size <= 0) {
			return "empty";
		}

		StringBuilder out = new StringBuilder(size * 3 + 2);

		int mark = position();

		for (;;) {
			int byteValue = get() & 0xFF;
			out.append((char) digits[byteValue >> 4]);
			out.append((char) digits[byteValue & 15]);
			if (--size <= 0) {
				break;
			}
			out.append(' ');
		}

		position(mark);

		if (truncate) {
			out.append("...");
		}

		return out.toString();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getString(CharsetDecoder decoder) throws CharacterCodingException {
		if (!hasRemaining()) {
			return "";
		}

		boolean utf16 = decoder.charset().name().startsWith("UTF-16");

		int oldPos = position();
		int oldLimit = limit();
		int end = -1;
		int newPos;

		if (!utf16) {
			end = indexOf((byte) 0x00);
			if (end < 0) {
				newPos = end = oldLimit;
			} else {
				newPos = end + 1;
			}
		} else {
			int i = oldPos;
			for (;;) {
				boolean wasZero = get(i) == 0;
				i++;

				if (i >= oldLimit) {
					break;
				}

				if (get(i) != 0) {
					i++;
					if (i >= oldLimit) {
						break;
					}

					continue;
				}

				if (wasZero) {
					end = i - 1;
					break;
				}
			}

			if (end < 0) {
				newPos = end = oldPos + (oldLimit - oldPos & 0xFFFFFFFE);
			} else {
				newPos = (end + 2 <= oldLimit ? end + 2 : end);
			}
		}

		if (oldPos == end) {
			position(newPos);
			return "";
		}

		limit(end);
		decoder.reset();

		int expectedLength = (int) (remaining() * decoder.averageCharsPerByte()) + 1;
		CharBuffer out = CharBuffer.allocate(expectedLength);
		for (;;) {
			CoderResult cr = (hasRemaining() ? decoder.decode(buf(), out, true) : decoder.flush(out));

			if (cr.isUnderflow()) {
				break;
			}

			if (cr.isOverflow()) {
				CharBuffer o = CharBuffer.allocate(out.capacity() + expectedLength);
				out.flip();
				o.put(out);
				out = o;
				continue;
			}

			if (cr.isError()) {
				// Revert the buffer back to the previous state.
				limit(oldLimit);
				position(oldPos);
				cr.throwException();
			}
		}

		limit(oldLimit);
		position(newPos);
		return out.flip().toString();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getString(int fieldSize, CharsetDecoder decoder) throws CharacterCodingException {
		checkFieldSize(fieldSize);

		if (fieldSize == 0) {
			return "";
		}

		if (!hasRemaining()) {
			return "";
		}

		boolean utf16 = decoder.charset().name().startsWith("UTF-16");

		if (utf16 && (fieldSize & 1) != 0) {
			throw new IllegalArgumentException("fieldSize is not even.");
		}

		int oldPos = position();
		int oldLimit = limit();
		int end = oldPos + fieldSize;

		if (oldLimit < end) {
			throw new BufferUnderflowException();
		}

		int i;
		if (!utf16) {
			for (i = oldPos; i < end; i++) {
				if (get(i) == 0) {
					break;
				}
			}
		} else {
			for (i = oldPos; i < end; i += 2) {
				if (get(i) == 0 && get(i + 1) == 0) {
					break;
				}
			}
		}
		limit(i);

		if (!hasRemaining()) {
			limit(oldLimit);
			position(end);
			return "";
		}
		decoder.reset();

		int expectedLength = (int) (remaining() * decoder.averageCharsPerByte()) + 1;
		CharBuffer out = CharBuffer.allocate(expectedLength);
		for (;;) {
			CoderResult cr = (hasRemaining() ? decoder.decode(buf(), out, true) : decoder.flush(out));

			if (cr.isUnderflow()) {
				break;
			}

			if (cr.isOverflow()) {
				CharBuffer o = CharBuffer.allocate(out.capacity() + expectedLength);
				out.flip();
				o.put(out);
				out = o;
				continue;
			}

			if (cr.isError()) {
				// Revert the buffer back to the previous state.
				limit(oldLimit);
				position(oldPos);
				cr.throwException();
			}
		}

		limit(oldLimit);
		position(end);
		return out.flip().toString();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IoBuffer putString(CharSequence val, CharsetEncoder encoder) throws CharacterCodingException {
		if (val.length() == 0) {
			return this;
		}

		CharBuffer in = CharBuffer.wrap(val);
		encoder.reset();

		int expandedState = 0;

		for (;;) {
			CoderResult cr = (in.hasRemaining() ? encoder.encode(in, buf(), true) : encoder.flush(buf()));

			if (cr.isUnderflow()) {
				break;
			}
			if (cr.isOverflow()) {
				if (isAutoExpand()) {
					switch (expandedState) {
						case 0:
							autoExpand((int) Math.ceil(in.remaining() * encoder.averageBytesPerChar()));
							expandedState++;
							break;
						case 1:
							autoExpand((int) Math.ceil(in.remaining() * encoder.maxBytesPerChar()));
							expandedState++;
							break;
						default:
							throw new RuntimeException("Expanded by "
									+ (int) Math.ceil(in.remaining() * encoder.maxBytesPerChar())
									+ " but that wasn't enough for '" + val + '\'');
					}
					continue;
				}
			} else {
				expandedState = 0;
			}
			cr.throwException();
		}
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IoBuffer putString(CharSequence val, int fieldSize, CharsetEncoder encoder) throws CharacterCodingException {
		checkFieldSize(fieldSize);

		if (fieldSize == 0) {
			return this;
		}

		autoExpand(fieldSize);

		boolean utf16 = encoder.charset().name().startsWith("UTF-16");

		if (utf16 && (fieldSize & 1) != 0) {
			throw new IllegalArgumentException("fieldSize is not even.");
		}

		int oldLimit = limit();
		int end = position() + fieldSize;

		if (oldLimit < end) {
			throw new BufferOverflowException();
		}

		if (val.length() == 0) {
			if (!utf16) {
				put((byte) 0x00);
			} else {
				put((byte) 0x00);
				put((byte) 0x00);
			}
			position(end);
			return this;
		}

		CharBuffer in = CharBuffer.wrap(val);
		limit(end);
		encoder.reset();

		for (;;) {
			CoderResult cr = (in.hasRemaining() ? encoder.encode(in, buf(), true) : encoder.flush(buf()));

			if (cr.isUnderflow() || cr.isOverflow()) {
				break;
			}
			cr.throwException();
		}

		limit(oldLimit);

		if (position() < end) {
			if (!utf16) {
				put((byte) 0x00);
			} else {
				put((byte) 0x00);
				put((byte) 0x00);
			}
		}

		position(end);
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int indexOf(byte b) {
		if (hasArray()) {
			int arrayOffset = arrayOffset();
			int beginPos = arrayOffset + position();
			int limit = arrayOffset + limit();
			byte[] array = array();

			for (int i = beginPos; i < limit; i++) {
				if (array[i] == b) {
					return i - arrayOffset;
				}
			}
		} else {
			int beginPos = position();
			int limit = limit();

			for (int i = beginPos; i < limit; i++) {
				if (get(i) == b) {
					return i;
				}
			}
		}

		return -1;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IoBuffer skip(int size) {
		autoExpand(size);
		return position(position() + size);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IoBuffer fill(byte value, int size) {
		autoExpand(size);
		int q = size >>> 3;
		int r = size & 7;

		if (q > 0) {
			int intValue = value & 0x000000FF | ( value << 8 ) & 0x0000FF00 | ( value << 16 ) & 0x00FF0000 | value << 24;
			long longValue = intValue & 0x00000000FFFFFFFFL | (long)intValue << 32;

			for (int i = q; i > 0; i--) {
				putLong(longValue);
			}
		}

		q = r >>> 2;
		r = r & 3;

		if (q > 0) {
			int intValue = value & 0x000000FF | ( value << 8 ) & 0x0000FF00 | ( value << 16 ) & 0x00FF0000 | value << 24;
			putInt(intValue);
		}

		q = r >> 1;
		r = r & 1;

		if (q > 0) {
			short shortValue = (short) (value & 0x00FF | value << 8);
			putShort(shortValue);
		}

		if (r > 0) {
			put(value);
		}

		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IoBuffer fillAndReset(byte value, int size) {
		autoExpand(size);
		int pos = position();
		try {
			fill(value, size);
		} finally {
			position(pos);
		}
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IoBuffer fill(int size) {
		autoExpand(size);
		int q = size >>> 3;
		int r = size & 7;

		for (int i = q; i > 0; i--) {
			putLong(0L);
		}

		q = r >>> 2;
		r = r & 3;

		if (q > 0) {
			putInt(0);
		}

		q = r >> 1;
		r = r & 1;

		if (q > 0) {
			putShort((short) 0);
		}

		if (r > 0) {
			put((byte) 0);
		}

		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IoBuffer fillAndReset(int size) {
		autoExpand(size);
		int pos = position();
		try {
			fill(size);
		} finally {
			position(pos);
		}

		return this;
	}

	/**
	 * This method forwards the call to {@link #expand(int)} only when
	 * <tt>autoExpand</tt> property is <tt>true</tt>.
	 */
	private IoBuffer autoExpand(int expectedRemaining) {
		if (isAutoExpand()) {
			expand(expectedRemaining, true);
		}
		return this;
	}

	/**
	 * This method forwards the call to {@link #expand(int)} only when
	 * <tt>autoExpand</tt> property is <tt>true</tt>.
	 */
	private IoBuffer autoExpand(int pos, int expectedRemaining) {
		if (isAutoExpand()) {
			expand(pos, expectedRemaining, true);
		}
		return this;
	}

	private static void checkFieldSize(int fieldSize) {
		if (fieldSize < 0) {
			throw new IllegalArgumentException("fieldSize cannot be negative: " + fieldSize);
		}
	}
}
