package jane.core;

import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import jane.core.SContext.Rec;
import jane.core.SContext.Safe;

/**
 * Map类型的安全修改类
 * <p>
 * 不支持value为null
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

	protected final Safe<?>	  _owner;
	protected final Map<K, V> _map;
	private SContext		  _sctx;
	protected Map<K, V>		  _changed;

	public SMap(Safe<?> owner, Map<K, V> map, SMapListener<K, V> listener)
	{
		_owner = owner;
		_map = map;
		if (listener != null)
		{
			Rec rec = owner.record();
			if (rec != null)
			{
				_changed = new HashMap<>();
				SContext.current().addOnCommit(() ->
				{
					if (!_changed.isEmpty())
						listener.onChanged(rec, _changed);
				});
			}
		}
	}

	protected SMap(Safe<?> owner, Map<K, V> map, Map<K, V> changed)
	{
		_owner = owner;
		_map = map;
		_changed = changed;
	}

	protected SContext sContext()
	{
		_owner.checkLock();
		if (_sctx != null)
			return _sctx;
		_owner.dirty();
		return _sctx = SContext.current();
	}

	@SuppressWarnings("unchecked")
	protected S safe(Object k, V v)
	{
		if (!(v instanceof Bean))
			return (S)v;
		Safe<?> s = ((Bean<?>)v).safe(_owner);
		if (_changed != null)
			s.onDirty(() -> _changed.put((K)k, v));
		return (S)s;
	}

	protected void addUndoPut(SContext ctx, K k, V vOld)
	{
		ctx.addOnRollback(() ->
		{
			if (vOld != null)
				_map.put(k, vOld);
			else
				_map.remove(k);
		});
	}

	protected void addUndoRemove(SContext ctx, K k, V vOld)
	{
		if (_changed != null)
			_changed.put(k, null);
		ctx.addOnRollback(() -> _map.put(k, vOld));
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
		return _map.containsValue(SContext.unsafe(v));
	}

	@Deprecated
	public V getUnsafe(Object k)
	{
		//noinspection SuspiciousMethodCalls
		return _map.get(k);
	}

	@Override
	public S get(Object k)
	{
		return safe(k, _map.get(k));
	}

	public V putDirect(K k, V v)
	{
		if (v == null)
			throw new NullPointerException();
		SContext ctx = sContext();
		if (_changed != null)
			_changed.put(k, v);
		v = _map.put(k, v);
		addUndoPut(ctx, k, v);
		return v;
	}

	@Override
	public S put(K k, S s)
	{
		return SContext.safeAlone(putDirect(k, SContext.unsafe(s)));
	}

	@Override
	public void putAll(Map<? extends K, ? extends S> m)
	{
		if (_map == m || this == m)
			return;
		for (Entry<? extends K, ? extends S> e : m.entrySet())
		{
			V v = SContext.unsafe(e.getValue());
			if (v != null)
				putDirect(e.getKey(), v);
		}
	}

	public void putAllDirect(Map<? extends K, ? extends V> m)
	{
		if (_map == m || this == m)
			return;
		for (Entry<? extends K, ? extends V> e : m.entrySet())
		{
			V v = e.getValue();
			if (v != null)
				putDirect(e.getKey(), v);
		}
	}

	@SuppressWarnings("unchecked")
	public V removeDirect(Object k)
	{
		SContext ctx = sContext();
		//noinspection SuspiciousMethodCalls
		V vOld = _map.remove(k);
		if (vOld == null)
			return null;
		addUndoRemove(ctx, (K)k, vOld);
		return vOld;
	}

	@Override
	public S remove(Object k)
	{
		return SContext.safeAlone(removeDirect(k));
	}

	@Override
	public void clear()
	{
		if (_map.isEmpty())
			return;
		SContext ctx = sContext();
		Map<K, V> saved = (_map instanceof LinkedHashMap ? new LinkedHashMap<>(_map) : new HashMap<>(_map));
		if (_changed != null)
		{
			for (K k : _map.keySet())
				_changed.put(k, null);
		}
		_map.clear();
		ctx.addOnRollback(() ->
		{
			_map.clear();
			_map.putAll(saved);
			saved.clear();
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

		public V setValueDirect(V v)
		{
			if (v == null)
				throw new NullPointerException();
			SContext ctx = sContext();
			K k = _e.getKey();
			if (_changed != null)
				_changed.put(k, v);
			v = _e.setValue(v);
			addUndoPut(ctx, k, v);
			return v;
		}

		@Override
		public S setValue(S s)
		{
			return SContext.safeAlone(setValueDirect(SContext.unsafe(s)));
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
			K k = _cur.getKey();
			V v = _cur.getValueUnsafe();
			SContext ctx = sContext();
			_it.remove();
			addUndoRemove(ctx, k, v);
		}
	}

	public final class SEntrySet extends AbstractSet<Entry<K, S>>
	{
		private Set<Entry<K, V>> _it;

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
			if (_it == null)
				_it = _map.entrySet();
			return _it.contains(e instanceof SMap.SEntry ? ((SEntry)e)._e : e);
		}

		@SuppressWarnings("unchecked")
		@Override
		public boolean remove(Object e)
		{
			if (e instanceof SMap.SEntry)
				e = ((SEntry)e)._e;
			if (!contains(e))
				return false;
			return e instanceof Entry && SMap.this.removeDirect(((Entry<K, ?>)e).getKey()) != null;
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

		@Override
		public SIterator<K> iterator()
		{
			//noinspection unchecked
			return new SIterator<>()
			{
				@SuppressWarnings("cast")
				@Override
				public K next()
				{
					//noinspection unchecked
					return (K)nextEntry().getKey();
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
			return SMap.this.removeDirect(k) != null;
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

		@Override
		public SIterator<S> iterator()
		{
			//noinspection unchecked
			return new SIterator<>()
			{
				@SuppressWarnings("cast")
				@Override
				public S next()
				{
					//noinspection unchecked
					return (S)nextEntry().getValue();
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

	public SMap<K, V, S> append(Map<K, V> map)
	{
		Util.appendDeep(map, _map);
		return this;
	}

	public SMap<K, V, S> assign(Map<K, V> map)
	{
		clear();
		Util.appendDeep(map, _map);
		return this;
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
