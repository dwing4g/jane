package jane.core;

import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import jane.core.SContext.Rec;
import jane.core.SContext.Safe;

/**
 * Map类型的安全修改类
 * <p>
 * 不支持key或value为null
 */
public class SMap<K, V, S> implements Map<K, S>, Cloneable
{
	public interface SMapListener<K, V>
	{
		/**
		 * 增删改统一一个回调接口
		 * @param rec 对应table及记录键值的封装
		 * @param changed 所有改动的kv对. 其中value为null的key表示被删除
		 */
		void onChanged(Rec rec, Map<K, V> changed);
	}

	protected final Safe<?>	  _parent;
	protected final Map<K, V> _map;
	protected final Map<K, V> _changed;

	public SMap(Safe<?> parent, Map<K, V> map, SMapListener<K, V> listener)
	{
		_parent = parent;
		_map = map;
		Rec rec;
		if (listener != null && (rec = parent.record()) != null)
		{
			_changed = new HashMap<>();
			SContext.current().addOnCommit(() ->
			{
				if (!_changed.isEmpty())
					listener.onChanged(rec, _changed);
			});
		}
		else
			_changed = null;
	}

	protected SMap(Safe<?> parent, Map<K, V> map, Map<K, V> changed)
	{
		_parent = parent;
		_map = map;
		_changed = changed;
	}

	protected SContext sContext()
	{
		_parent.checkLock();
		_parent.dirty();
		return SContext.current();
	}

	@Deprecated
	public Map<K, V> unsafe()
	{
		return _map;
	}

	@SuppressWarnings("unchecked")
	protected S safe(Object k, V v)
	{
		if (!(v instanceof Bean))
			return (S)v;
		Safe<?> s = ((Bean<?>)v).safe(_parent);
		if (_changed != null)
			s.onDirty(() -> _changed.put((K)k, v));
		return (S)s;
	}

	protected void addUndoPut(SContext ctx, K k, V vOld)
	{
		ctx.addOnRollback(() ->
		{
			V v;
			if (vOld != null)
			{
				SContext.checkStoreAll(vOld); // 这里不应该检查失败
				v = _map.put(k, vOld);
			}
			else
				v = _map.remove(k);
			SContext.unstoreAll(v);
		});
	}

	protected void addUndoRemove(SContext ctx, K k, V vOld)
	{
		if (_changed != null)
			_changed.put(k, null);
		ctx.addOnRollback(() ->
		{
			SContext.checkStoreAll(vOld); // 这里不应该检查失败
			_map.put(k, vOld); // 一定返回null,所以不用调SContext.unstore
		});
	}

	@Override
	public int size()
	{
		return _map.size();
	}

	@Override
	public boolean isEmpty()
	{
		return _map.isEmpty();
	}

	@Override
	public boolean containsKey(Object k)
	{
		return _map.containsKey(k);
	}

	@Override
	public boolean containsValue(Object v)
	{
		return _map.containsValue(SContext.unwrap(v));
	}

	@Override
	public S get(Object k)
	{
		return safe(k, _map.get(k));
	}

	@Deprecated
	public V putUnsafe(K k, V v)
	{
		SContext ctx = sContext();
		SContext.checkStoreAll(v);
		if (_changed != null)
			_changed.put(k, v);
		v = SContext.unstoreAll(_map.put(k, v));
		addUndoPut(ctx, k, v);
		return v;
	}

	public void putDirect(K k, V v)
	{
		putUnsafe(k, v);
	}

	@Override
	public S put(K k, S s)
	{
		return SContext.safeAlone(putUnsafe(k, SContext.unwrap(s)));
	}

	private void putAll(SContext ctx, Object[] saved) // (K|V)[] saved
	{
		for (int i = 0, n = saved.length; i < n;)
		{
			@SuppressWarnings("unchecked")
			K k = (K)saved[i++];
			if (k == null)
				break;
			@SuppressWarnings("unchecked")
			V v = (V)saved[i];
			if (_changed != null)
				_changed.put(k, v);
			saved[i++] = SContext.unstoreAll(_map.put(k, v));
		}
		ctx.addOnRollback(() ->
		{
			for (int j = 0, n2 = saved.length; j < n2;)
			{
				@SuppressWarnings("unchecked")
				K k = (K)saved[j++];
				if (k == null)
					break;
				@SuppressWarnings("unchecked")
				V v = (V)saved[j++];
				if (v != null)
				{
					SContext.checkStoreAll(v); // 这里不应该检查失败
					v = _map.put(k, v);
				}
				else
					v = _map.remove(k);
				SContext.unstoreAll(v);
			}
		});
	}

	public void putAllDirect(Map<? extends K, ? extends V> m)
	{
		int n = m.size();
		if (n <= 0)
			return;
		if (_map == m || this == m)
			return;
		SContext ctx = sContext();
		Object[] saved = new Object[n * 2];
		int i = 0;
		for (Entry<? extends K, ? extends V> e : m.entrySet())
		{
			K k = e.getKey();
			if (k == null)
				continue;
			V v = e.getValue();
			if (v == null)
				continue;
			SContext.checkStoreAll(v);
			saved[i++] = k;
			saved[i++] = v;
		}
		if (i > 0)
			putAll(ctx, saved);
	}

	@Override
	public void putAll(Map<? extends K, ? extends S> m)
	{
		int n = m.size();
		if (n <= 0)
			return;
		if (_map == m || this == m)
			return;
		SContext ctx = sContext();
		Object[] saved = new Object[n * 2];
		int i = 0;
		for (Entry<? extends K, ? extends S> e : m.entrySet())
		{
			K k = e.getKey();
			if (k == null)
				continue;
			V v = SContext.unwrap(e.getValue());
			if (v == null)
				continue;
			SContext.checkStoreAll(v);
			saved[i++] = k;
			saved[i++] = v;
		}
		if (i > 0)
			putAll(ctx, saved);
	}

	@SuppressWarnings("unchecked")
	@Deprecated
	public V removeUnsafe(Object k)
	{
		SContext ctx = sContext();
		//noinspection SuspiciousMethodCalls
		V vOld = SContext.unstoreAll(_map.remove(k));
		if (vOld == null)
			return null;
		addUndoRemove(ctx, (K)k, vOld);
		return vOld;
	}

	public boolean removeDirect(Object k)
	{
		return removeUnsafe(k) != null;
	}

	@Override
	public S remove(Object k)
	{
		return SContext.safeAlone(removeUnsafe(k));
	}

	@Override
	public void clear()
	{
		int n = _map.size();
		if (n <= 0)
			return;
		SContext ctx = sContext();
		@SuppressWarnings("unchecked")
		Entry<K, V>[] saved = (Entry<K, V>[])new Entry[n];
		int i = 0;
		for (Entry<K, V> e : _map.entrySet())
		{
			SContext.unstoreAll(e.getValue());
			saved[i++] = e;
		}
		_map.clear();
		ctx.addOnRollback(() ->
		{
			_map.clear();
			for (int j = 0; j < n; j++)
			{
				Entry<K, V> e = saved[j];
				V v = e.getValue();
				SContext.checkStoreAll(v); // 这里不应该检查失败
				_map.put(e.getKey(), v); // 一定返回null,所以不用调SContext.unstore
			}
		});
	}

	public final class SEntry implements Entry<K, S>
	{
		private final Entry<K, V> _e;

		SEntry(Entry<K, V> e)
		{
			_e = e;
		}

		@Override
		public K getKey()
		{
			return _e.getKey();
		}

		@Deprecated
		public V getValueUnsafe()
		{
			return _e.getValue();
		}

		@Override
		public S getValue()
		{
			return safe(_e.getKey(), _e.getValue());
		}

		@Deprecated
		public V setValueUnsafe(V v)
		{
			SContext ctx = sContext();
			SContext.checkStoreAll(v);
			K k = _e.getKey();
			if (_changed != null)
				_changed.put(k, v);
			v = SContext.unstoreAll(_e.setValue(v));
			addUndoPut(ctx, k, v);
			return v;
		}

		public void setValueDirect(V v)
		{
			setValueUnsafe(v);
		}

		@Override
		public S setValue(S s)
		{
			return SContext.safeAlone(setValueUnsafe(SContext.unwrap(s)));
		}

		@Override
		public int hashCode()
		{
			return _e.hashCode();
		}

		@Override
		public boolean equals(Object o)
		{
			return this == o || _e.equals(o);
		}
	}

	public abstract class SIterator<E> implements Iterator<E>
	{
		private final Iterator<Entry<K, V>>	_it	= _map.entrySet().iterator();
		private SEntry						_cur;

		private SIterator()
		{
		}

		@Override
		public boolean hasNext()
		{
			return _it.hasNext();
		}

		public SEntry nextEntry()
		{
			return _cur = new SEntry(_it.next());
		}

		@Override
		public void remove()
		{
			SContext ctx = sContext();
			K k = _cur.getKey();
			V v = _cur.getValueUnsafe();
			_it.remove();
			addUndoRemove(ctx, k, SContext.unstoreAll(v));
		}
	}

	public final class SEntrySet extends AbstractSet<Entry<K, S>>
	{
		private Set<Entry<K, V>> _es;

		private SEntrySet()
		{
		}

		@Override
		public SIterator<Entry<K, S>> iterator()
		{
			//noinspection unchecked
			return new SIterator<>()
			{
				@Override
				public SEntry next()
				{
					//noinspection unchecked
					return nextEntry();
				}
			};
		}

		@Override
		public int size()
		{
			return SMap.this.size();
		}

		@SuppressWarnings("unchecked")
		@Override
		public boolean contains(Object e)
		{
			if (_es == null)
				_es = _map.entrySet();
			return _es.contains(e instanceof SMap.SEntry ? ((SEntry)e)._e : e);
		}

		@SuppressWarnings("unchecked")
		@Override
		public boolean remove(Object e)
		{
			if (e instanceof SMap.SEntry)
				e = ((SEntry)e)._e;
			if (!contains(e))
				return false;
			return e instanceof Entry && SMap.this.removeDirect(((Entry<K, ?>)e).getKey());
		}

		@Override
		public void clear()
		{
			SMap.this.clear();
		}
	}

	public final class SKeySet extends AbstractSet<K>
	{
		private SKeySet()
		{
		}

		@SuppressWarnings("unused")
		@Override
		public SIterator<K> iterator()
		{
			return new SIterator<K>()
			{
				@Override
				public K next()
				{
					return nextEntry().getKey();
				}
			};
		}

		@Override
		public int size()
		{
			return SMap.this.size();
		}

		@Override
		public boolean contains(Object k)
		{
			return SMap.this.containsKey(k);
		}

		@Override
		public boolean remove(Object k)
		{
			return SMap.this.removeDirect(k);
		}

		@Override
		public void clear()
		{
			SMap.this.clear();
		}
	}

	public final class SValues extends AbstractCollection<S>
	{
		private SValues()
		{
		}

		@SuppressWarnings("unused")
		@Override
		public SIterator<S> iterator()
		{
			return new SIterator<S>()
			{
				@Override
				public S next()
				{
					return nextEntry().getValue();
				}
			};
		}

		@Override
		public int size()
		{
			return SMap.this.size();
		}

		@Override
		public boolean contains(Object v)
		{
			return SMap.this.containsValue(v);
		}

		@Override
		public void clear()
		{
			SMap.this.clear();
		}
	}

	@Override
	public SEntrySet entrySet()
	{
		return new SEntrySet();
	}

	@Override
	public SKeySet keySet()
	{
		return new SKeySet();
	}

	@Override
	public SValues values()
	{
		return new SValues();
	}

	public boolean foreachFilter(Predicate<V> filter, Predicate<S> consumer)
	{
		for (Entry<K, V> entry : _map.entrySet())
		{
			V v = entry.getValue();
			if (filter.test(v) && !consumer.test(safe(entry.getKey(), v)))
				return false;
		}
		return true;
	}

	public boolean foreachFilter(Predicate<V> filter, BiPredicate<K, S> consumer)
	{
		for (Entry<K, V> entry : _map.entrySet())
		{
			K k = entry.getKey();
			V v = entry.getValue();
			if (filter.test(v) && !consumer.test(k, safe(k, v)))
				return false;
		}
		return true;
	}

	public boolean foreachFilter(BiPredicate<K, V> filter, Predicate<S> consumer)
	{
		for (Entry<K, V> entry : _map.entrySet())
		{
			K k = entry.getKey();
			V v = entry.getValue();
			if (filter.test(k, v) && !consumer.test(safe(k, v)))
				return false;
		}
		return true;
	}

	public boolean foreachFilter(BiPredicate<K, V> filter, BiPredicate<K, S> consumer)
	{
		for (Entry<K, V> entry : _map.entrySet())
		{
			K k = entry.getKey();
			V v = entry.getValue();
			if (filter.test(k, v) && !consumer.test(k, safe(k, v)))
				return false;
		}
		return true;
	}

	public void appendTo(Map<K, V> map)
	{
		Util.appendDeep(_map, map);
	}

	public void cloneTo(Map<K, V> map)
	{
		map.clear();
		Util.appendDeep(_map, map);
	}

	@SuppressWarnings("unchecked")
	@Override
	public final Map<K, V> clone()
	{
		return Util.appendDeep(_map, Util.newInstance(_map.getClass()));
	}

	@Override
	public int hashCode()
	{
		return _map.hashCode();
	}

	@Override
	public boolean equals(Object o)
	{
		return this == o || _map.equals(o);
	}

	@Override
	public String toString()
	{
		return _map.toString();
	}
}
