/*
 *  Copyright (c) 2012 Jan Kotek
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package jane.core.map;

/**
 * Same as 'java.util.Map' but uses primitive 'long' keys to minimise boxing (and GC) overhead.
 */
public abstract class LongMap<V> implements Iterable<V>
{
	/**
	 * Returns the number of elements in this map.
	 */
	public abstract int size();

	/**
	 * Returns whether this map is empty.
	 *
	 * @return {@code true} if this map has no elements, {@code false} otherwise.
	 * @see #size()
	 */
	public abstract boolean isEmpty();

	/**
	 * Returns the value of the mapping with the specified key.
	 *
	 * @param key the key.
	 * @return the value of the mapping with the specified key, or {@code null}
	 *         if no mapping for the specified key is found.
	 */
	public abstract V get(long key);

	/**
	 * Maps the specified key to the specified value.
	 *
	 * @param key the key.
	 * @param value the value.
	 * @return the value of any previous mapping with the specified key or
	 *         {@code null} if there was no such mapping.
	 */
	public abstract V put(long key, V value);

	/**
	 * Removes the mapping from this map
	 *
	 * @param key to remove
	 * @return value contained under this key, or null if value did not exist
	 */
	public abstract V remove(long key);

	public abstract boolean remove(long key, V value);

	/**
	 * Removes all mappings from this hash map, leaving it empty.
	 *
	 * @see #isEmpty
	 * @see #size
	 */
	public abstract void clear();

	public abstract LongIterator keyIterator();

	public abstract MapIterator<V> entryIterator();

	public interface LongIterator
	{
		boolean hasNext();

		long next();

		void remove();
	}

	public interface MapIterator<V>
	{
		boolean moveToNext();

		long key();

		V value();

		void remove();
	}

	@Override
	public String toString()
	{
		final StringBuilder sb = new StringBuilder().append(getClass().getSimpleName()).append('[');
		boolean first = true;
		for (MapIterator<V> it = entryIterator(); it.moveToNext();)
		{
			if (first)
				first = false;
			else
				sb.append(", ");
			sb.append(it.key()).append(':').append(it.value());
		}
		return sb.append(']').toString();
	}
}
