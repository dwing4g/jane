package jane.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全修改的上下文类
 * <p>
 * 管理当前线程的回滚和提交<br>
 * 由ThreadLocal管理全部的的上下文
 */
public final class SContext
{
	public static abstract class Wrap<B>
	{
		protected final B       _bean;
		protected final Wrap<?> _owner;
		protected SContext      _sCtx;
		private Object          _key;
		private Runnable        _onDirty;
		protected boolean       _fullUndo;
		private boolean         _dirty;

		protected Wrap(B bean, Wrap<?> parent)
		{
			_bean = bean;
			_owner = (parent != null ? parent.owner() : this);
		}

		public B unsafe()
		{
			return _bean;
		}

		public Wrap<?> owner()
		{
			return _owner;
		}

		public Object key()
		{
			return _key;
		}

		void key(Object key)
		{
			_key = key;
		}

		public boolean isDirtyAndClear()
		{
			boolean r = _dirty;
			_dirty = false;
			return r;
		}

		public void onDirty(Runnable onDirty)
		{
			_onDirty = onDirty;
		}

		public void dirty()
		{
			if(_owner == this)
				_dirty = true;
			else
				_owner.dirty();
			if(_onDirty != null)
			{
				_onDirty.run();
				_onDirty = null;
			}
		}

		protected boolean initSContext()
		{
			if(_fullUndo) return false;
			if(_sCtx == null)
			{
				if(_onDirty != null)
				{
					_onDirty.run();
					_onDirty = null;
				}
				_owner.dirty();
				_sCtx = current();
			}
			return true;
		}
	}

	public static abstract class Safe<B extends Bean<B>> extends Wrap<B> implements Comparable<B>, Cloneable
	{
		protected Safe(B bean, Wrap<?> parent)
		{
			super(bean, parent);
		}

		public void addFullUndo()
		{
			if(!initSContext()) return;
			_sCtx.addOnRollback(new Runnable()
			{
				private final B _saved = _bean.clone();

				@Override
				public void run()
				{
					_bean.assign(_saved);
				}
			});
			_fullUndo = true;
		}

		public void reset()
		{
			addFullUndo();
			_bean.reset();
		}

		public void assign(B b)
		{
			if(b == _bean) return;
			addFullUndo();
			_bean.assign(b);
		}

		public OctetsStream marshal(OctetsStream s)
		{
			return _bean.marshal(s);
		}

		public OctetsStream unmarshal(OctetsStream s) throws MarshalException
		{
			addFullUndo();
			return _bean.unmarshal(s);
		}

		@Override
		public B clone()
		{
			return _bean.clone();
		}

		@Override
		public int hashCode()
		{
			return _bean.hashCode();
		}

		@Override
		public boolean equals(Object o)
		{
			return _bean.equals(o);
		}

		@Override
		public int compareTo(B b)
		{
			return _bean.compareTo(b);
		}

		@Override
		public String toString()
		{
			return _bean.toString();
		}

		public StringBuilder toJson(StringBuilder s)
		{
			return _bean.toJson(s);
		}

		public StringBuilder toLua(StringBuilder s)
		{
			return _bean.toLua(s);
		}
	}

	private static final class Record<K, V extends Bean<V>, S extends Safe<V>>
	{
		private final Table<K, V, S> _table;
		private final K              _key;
		private final S              _value;

		private Record(Table<K, V, S> table, K key, S value)
		{
			_table = table;
			_key = key;
			_value = value;
		}
	}

	private static final class RecordLong<V extends Bean<V>, S extends Safe<V>>
	{
		private final TableLong<V, S> _table;
		private final long            _key;
		private final S               _value;

		private RecordLong(TableLong<V, S> table, long key, S value)
		{
			_table = table;
			_key = key;
			_value = value;
		}
	}

	private static final ThreadLocal<SContext> _tlList;
	private final List<Record<?, ?, ?>>        _records     = new ArrayList<Record<?, ?, ?>>();
	private final List<RecordLong<?, ?>>       _recordLongs = new ArrayList<RecordLong<?, ?>>();
	private final List<Runnable>               _onRollbacks = new ArrayList<Runnable>();
	private final List<Runnable>               _onCommits   = new ArrayList<Runnable>();

	static
	{
		_tlList = new ThreadLocal<SContext>()
		{
			@Override
			protected SContext initialValue()
			{
				return new SContext();
			}
		};
	}

	public static SContext current()
	{
		return _tlList.get();
	}

	<K, V extends Bean<V>, S extends Safe<V>> S addRecord(Table<K, V, S> table, K key, V value)
	{
		@SuppressWarnings("unchecked")
		S vSafe = (S)value.safe(null);
		vSafe.key(key);
		_records.add(new Record<K, V, S>(table, key, vSafe));
		return vSafe;
	}

	<V extends Bean<V>, S extends Safe<V>> S addRecord(TableLong<V, S> table, long key, V value)
	{
		@SuppressWarnings("unchecked")
		S vSafe = (S)value.safe(null);
		vSafe.key(key);
		_recordLongs.add(new RecordLong<V, S>(table, key, vSafe));
		return vSafe;
	}

	public void addOnCommit(Runnable r)
	{
		_onCommits.add(r);
	}

	public void addOnRollback(Runnable r)
	{
		_onRollbacks.add(r);
	}

	public void commit()
	{
		_onRollbacks.clear();

		for(Record<?, ?, ?> r : _records)
		{
			if(r._value.isDirtyAndClear())
			    r._table.modifySafe(r._key, r._value.unsafe());
		}
		_records.clear();

		for(RecordLong<?, ?> r : _recordLongs)
		{
			if(r._value.isDirtyAndClear())
			    r._table.modifySafe(r._key, r._value.unsafe());
		}
		_recordLongs.clear();

		for(Runnable r : _onCommits)
		{
			try
			{
				r.run();
			}
			catch(Throwable e)
			{
				Log.log.error("oncommit exception:", e);
			}
		}
		_onCommits.clear();
	}

	public void rollback()
	{
		_records.clear();
		_recordLongs.clear();
		_onCommits.clear();

		for(int i = _onRollbacks.size(); --i >= 0;)
		{
			try
			{
				_onRollbacks.get(i).run();
			}
			catch(Throwable e)
			{
				Log.log.error("rollback exception:", e);
			}
		}
		_onRollbacks.clear();
	}
}
