package jane.core;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.Map;
import java.util.function.Supplier;
import jane.core.map.LongMap;

final class StrongRef<V> implements Supplier<V>
{
	private final V _value;

	StrongRef(V v)
	{
		_value = v;
	}

	@Override
	public V get()
	{
		return _value;
	}
}

public abstract class CacheRef<V> extends SoftReference<V> implements Supplier<V>, Runnable
{
	private static final ReferenceQueue<Object>	_refQueue = new ReferenceQueue<>();
	private static long							_refRemoveCount;

	static
	{
		Thread thread = new Thread(() ->
		{
			try
			{
				for (;; ++_refRemoveCount)
					((Runnable)_refQueue.remove()).run();
			}
			catch (InterruptedException e)
			{
				Log.error("SoftRefCleanerThread interrupted:", e);
			}
		}, "SoftRefCleanerThread");
		thread.setDaemon(true);
		thread.start();
	}

	public static long getRefRemoveCount()
	{
		return _refRemoveCount;
	}

	CacheRef(V v)
	{
		super(v, _refQueue);
	}
}

final class CacheRefK<K, V> extends CacheRef<V>
{
	private final Map<K, Supplier<V>> _map;
	private final K					  _key;

	CacheRefK(Map<K, Supplier<V>> m, K k, V v)
	{
		super(v);
		_map = m;
		_key = k;
	}

	@Override
	public void run()
	{
		Supplier<V> oldRef = _map.get(_key);
		if (oldRef != null && oldRef.get() == null)
			_map.remove(_key, oldRef);
	}
}

final class CacheRefLong<V> extends CacheRef<V>
{
	private final LongMap<Supplier<V>> _map;
	private final long				   _key;

	CacheRefLong(LongMap<Supplier<V>> m, long k, V v)
	{
		super(v);
		_map = m;
		_key = k;
	}

	@Override
	public void run()
	{
		Supplier<V> oldRef = _map.get(_key);
		if (oldRef != null && oldRef.get() == null)
			_map.remove(_key, oldRef);
	}
}
