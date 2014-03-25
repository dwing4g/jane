package jane.core;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class UndoContext
{
	public interface Safe<B extends Bean<B>>
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

	public abstract static class UndoBase implements Undo
	{
		protected final Safe<?> _obj;
		protected final Field   _field;

		public UndoBase(Safe<?> obj, Field field)
		{
			_obj = obj;
			_field = field;
		}
	}

	public static final class UndoBoolean extends UndoBase
	{
		private final boolean _saved;

		public UndoBoolean(Safe<?> obj, Field field, boolean v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.setBoolean(_obj, _saved);
		}
	}

	public static final class UndoChar extends UndoBase
	{
		private final char _saved;

		public UndoChar(Safe<?> obj, Field field, char v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.setChar(_obj, _saved);
		}
	}

	public static final class UndoByte extends UndoBase
	{
		private final byte _saved;

		public UndoByte(Safe<?> obj, Field field, byte v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.setByte(_obj, _saved);
		}
	}

	public static final class UndoShort extends UndoBase
	{
		private final short _saved;

		public UndoShort(Safe<?> obj, Field field, short v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.setShort(_obj, _saved);
		}
	}

	public static final class UndoInteger extends UndoBase
	{
		private final int _saved;

		public UndoInteger(Safe<?> obj, Field field, int v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.setInt(_obj, _saved);
		}
	}

	public static final class UndoLong extends UndoBase
	{
		private final long _saved;

		public UndoLong(Safe<?> obj, Field field, long v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.setLong(_obj, _saved);
		}
	}

	public static final class UndoFloat extends UndoBase
	{
		private final float _saved;

		public UndoFloat(Safe<?> obj, Field field, float v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.setFloat(_obj, _saved);
		}
	}

	public static final class UndoDouble extends UndoBase
	{
		private final double _saved;

		public UndoDouble(Safe<?> obj, Field field, double v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.setDouble(_obj, _saved);
		}
	}

	public static final class UndoOctets extends UndoBase
	{
		private final Octets _saved;

		public UndoOctets(Safe<?> obj, Field field, Octets v)
		{
			super(obj, field);
			_saved = v.clone();
		}

		@Override
		public void rollback() throws Exception
		{
			_field.set(_obj, _saved);
		}
	}

	public static final class UndoString extends UndoBase
	{
		private final String _saved;

		public UndoString(Safe<?> obj, Field field, String v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.set(_obj, _saved);
		}
	}

	private static final class Record<K, V extends Bean<V>, S extends Safe<V>>
	{
		private final Table<K, V> _table;
		private final K           _key;
		private final S           _value;

		private Record(Table<K, V> table, K key, S value)
		{
			_table = table;
			_key = key;
			_value = value;
		}
	}

	private static final class RecordLong<V extends Bean<V>, S extends Safe<V>>
	{
		private final TableLong<V> _table;
		private final long         _key;
		private final S            _value;

		private RecordLong(TableLong<V> table, long key, S value)
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

	<K, V extends Bean<V>, S extends Safe<V>> S addRecord(Table<K, V> table, K key, V value)
	{
		@SuppressWarnings("unchecked")
		S v_safe = (S)value.safe(null);
		_records.add(new Record<K, V, S>(table, key, v_safe));
		return v_safe;
	}

	<V extends Bean<V>, S extends Safe<V>> S addRecord(TableLong<V> table, long key, V value)
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
	}

	void rollback()
	{
		_records.clear();
		_recordlongs.clear();

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
