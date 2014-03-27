package jane.core;

import java.util.ArrayList;
import java.util.List;

public class UndoContext
{
	public interface Safe<B>
	{
		B unsafe();

		Safe<?> owner();

		boolean isDirtyAndClear();

		void dirty();
	}

	public interface Undo
	{
		void rollback() throws Exception;
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

	@SuppressWarnings("unchecked")
	<K, V extends Bean<V>, S extends Safe<V>> void commit()
	{
		_undos.clear();

		for(Record<?, ?, ?> record : _records)
		{
			Record<K, V, S> r = (Record<K, V, S>)record;
			if(r._value.isDirtyAndClear())
			    r._table.modify(r._key, r._value.unsafe());
		}
		_records.clear();

		for(RecordLong<?, ?> record : _recordlongs)
		{
			RecordLong<V, S> r = (RecordLong<V, S>)record;
			if(r._value.isDirtyAndClear())
			    r._table.modify(r._key, r._value.unsafe());
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
