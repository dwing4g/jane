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
		protected SContext      _sctx;
		protected boolean       _fullundo;
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

		public boolean isDirtyAndClear()
		{
			boolean r = _dirty;
			_dirty = false;
			return r;
		}

		public void dirty()
		{
			if(_owner == this)
				_dirty = true;
			else
				_owner.dirty();
		}

		protected boolean initSContext()
		{
			if(_fullundo) return false;
			if(_sctx == null)
			{
				_owner.dirty();
				_sctx = current();
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
			_sctx.addOnRollback(new Runnable()
			{
				private final B _saved = _bean.clone();

				@Override
				public void run()
				{
					_bean.assign(_saved);
				}
			});
			_fullundo = true;
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

	private static final ThreadLocal<SContext> _tl_list;
	private final List<Record<?, ?, ?>>        _records     = new ArrayList<Record<?, ?, ?>>();
	private final List<RecordLong<?, ?>>       _recordlongs = new ArrayList<RecordLong<?, ?>>();
	private final List<Runnable>               _onrollbacks = new ArrayList<Runnable>();
	private final List<Runnable>               _oncommits   = new ArrayList<Runnable>();

	static
	{
		_tl_list = new ThreadLocal<SContext>()
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
		return _tl_list.get();
	}

	<K, V extends Bean<V>, S extends Safe<V>> S addRecord(Table<K, V, S> table, K key, V value)
	{
		@SuppressWarnings("unchecked")
		S v_safe = (S)value.safe(null);
		_records.add(new Record<K, V, S>(table, key, v_safe));
		return v_safe;
	}

	<V extends Bean<V>, S extends Safe<V>> S addRecord(TableLong<V, S> table, long key, V value)
	{
		@SuppressWarnings("unchecked")
		S v_safe = (S)value.safe(null);
		_recordlongs.add(new RecordLong<V, S>(table, key, v_safe));
		return v_safe;
	}

	public void addOnCommit(Runnable r)
	{
		_oncommits.add(r);
	}

	public void addOnRollback(Runnable r)
	{
		_onrollbacks.add(r);
	}

	public void commit()
	{
		_onrollbacks.clear();

		for(Record<?, ?, ?> r : _records)
		{
			if(r._value.isDirtyAndClear())
			    r._table.modifySafe(r._key, r._value.unsafe());
		}
		_records.clear();

		for(RecordLong<?, ?> r : _recordlongs)
		{
			if(r._value.isDirtyAndClear())
			    r._table.modifySafe(r._key, r._value.unsafe());
		}
		_recordlongs.clear();

		for(Runnable r : _oncommits)
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
		_oncommits.clear();
	}

	public void rollback()
	{
		_records.clear();
		_recordlongs.clear();
		_oncommits.clear();

		for(int i = _onrollbacks.size(); --i >= 0;)
		{
			try
			{
				_onrollbacks.get(i).run();
			}
			catch(Throwable e)
			{
				Log.log.error("rollback exception:", e);
			}
		}
		_onrollbacks.clear();
	}
}
