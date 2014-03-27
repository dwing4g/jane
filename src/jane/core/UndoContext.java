package jane.core;

import java.util.ArrayList;
import java.util.List;

public final class UndoContext
{
	public interface Undo
	{
		void rollback() throws Exception;
	}

	public static abstract class Wrap<B>
	{
		protected final B _bean;

		protected Wrap(B bean)
		{
			_bean = bean;
		}

		public B unsafe()
		{
			return _bean;
		}
	}

	public static abstract class Safe<B extends Bean<B>> extends Wrap<B> implements Comparable<B>, Cloneable
	{
		protected final Safe<?> _owner;
		protected UndoContext   _undoctx;
		private boolean         _dirty;
		protected boolean       _fullundo;

		protected Safe(B bean, UndoContext.Safe<?> parent)
		{
			super(bean);
			_owner = (parent != null ? parent.owner() : this);
		}

		public Safe<?> owner()
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

		protected boolean initUndoContext()
		{
			if(_fullundo) return false;
			if(_undoctx == null)
			{
				_owner.dirty();
				_undoctx = UndoContext.current();
			}
			return true;
		}

		public void addFullUndo()
		{
			if(!initUndoContext()) return;
			_undoctx.add(new UndoContext.Undo()
			{
				private final B _saved = _bean.clone();

				@Override
				public void rollback()
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

	private static ThreadLocal<UndoContext> _tl_list;
	private final List<Record<?, ?, ?>>     _records     = new ArrayList<Record<?, ?, ?>>();
	private final List<RecordLong<?, ?>>    _recordlongs = new ArrayList<RecordLong<?, ?>>();
	private final List<Undo>                _undos       = new ArrayList<Undo>();
	private final List<Runnable>            _oncommits   = new ArrayList<Runnable>();

	static
	{
		_tl_list = new ThreadLocal<UndoContext>()
		{
			@Override
			protected UndoContext initialValue()
			{
				return new UndoContext();
			}
		};
	}

	public static UndoContext current()
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

	public void add(Undo undo)
	{
		_undos.add(undo);
	}

	public void addOnCommit(Runnable r)
	{
		_oncommits.add(r);
	}

	void commit()
	{
		_undos.clear();

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

	void rollback()
	{
		_records.clear();
		_recordlongs.clear();
		_oncommits.clear();

		for(int i = _undos.size(); --i >= 0;)
		{
			try
			{
				_undos.get(i).rollback();
			}
			catch(Exception e)
			{
				Log.log.error("rollback exception:", e);
			}
		}
		_undos.clear();
	}
}
