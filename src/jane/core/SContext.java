package jane.core;

import java.util.ArrayList;

/**
 * 安全修改的上下文类
 * <p>
 * 管理当前线程的回滚和提交<br>
 * 由ProcThread管理上下文
 */
public final class SContext {
	public abstract static class Safe<B extends Bean<B>> implements Comparable<B>, Cloneable {
		protected final B _bean;
		private final Safe<?> _parent;
		private Rec _rec;
		private Runnable _onDirty;
		private boolean _dirty;
		private boolean _fullUndo;

		protected Safe(B bean, Safe<?> parent) {
			_bean = bean;
			_parent = parent;
		}

		@Deprecated
		public B unsafe() {
			return _bean;
		}

		public Safe<?> parent() {
			return _parent;
		}

		public Rec record() {
			for (Safe<?> s = this; ; ) {
				Rec rec = s._rec;
				if (rec != null)
					return rec;
				if ((s = s._parent) == null)
					return null;
			}
		}

		void record(Rec rec) {
			_rec = rec;
		}

		public void checkLock() {
			for (Safe<?> s = this; ; ) {
				Rec rec = s._rec;
				if (rec != null) {
					rec.checkLock();
					return;
				}
				if ((s = s._parent) == null)
					return;
			}
		}

		public boolean isDirty() {
			return _dirty;
		}

		public void onDirty(Runnable onDirty) {
			_onDirty = onDirty;
		}

		public void dirty() {
			for (Safe<?> s = this; ; ) {
				if (s._dirty)
					return;
				s._dirty = true;
				Runnable onDirty = s._onDirty;
				if (onDirty != null) {
					s._onDirty = null;
					onDirty.run();
				}
				if ((s = s._parent) == null)
					return;
			}
		}

		protected SContext safeContext() {
			checkLock();
			if (_fullUndo)
				return null;
			dirty();
			return current();
		}

		public void addFullUndo() {
			SContext sctx = safeContext();
			if (sctx == null)
				return;
			B saved = _bean.clone();
			sctx.addOnRollback(() -> _bean.assign(saved));
			_fullUndo = true;
		}

		public void reset() {
			addFullUndo();
			_bean.reset();
		}

		public void assign(B b) {
			if (b == _bean)
				return;
			addFullUndo();
			_bean.assign(b);
		}

		public void assign(Safe<B> s) {
			assign(s._bean);
		}

		public Octets marshal(Octets s) {
			return _bean.marshal(s);
		}

		public OctetsStream unmarshal(OctetsStream s) throws MarshalException {
			addFullUndo();
			return _bean.unmarshal(s);
		}

		@Override
		public B clone() {
			return _bean.clone();
		}

		@Override
		public int hashCode() {
			return _bean.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			return _bean.equals(o instanceof Safe ? ((Safe<?>)o)._bean : o);
		}

		@Override
		public int compareTo(B b) {
			return _bean.compareTo(b);
		}

		@Override
		public String toString() {
			return _bean.toString();
		}
	}

	public interface Rec {
		TableBase<?> getTable();

		Object getKey();

		long getKeyLong();

		Object getValue();

		void checkLock();
	}

	static final class Record<K, V extends Bean<V>, S extends Safe<V>> implements Rec {
		private final Table<K, V, S> _table;
		private final K _key;
		private final S _value;
		private final int _lockId;

		Record(Table<K, V, S> table, K key, S value) {
			_table = table;
			_key = key;
			_value = value;
			_lockId = table.lockId(key);
		}

		@Override
		public TableBase<V> getTable() {
			return _table;
		}

		@Override
		public K getKey() {
			return _key;
		}

		@Override
		public long getKeyLong() {
			return _key instanceof Number ? ((Number)_key).longValue() : 0;
		}

		@Override
		public S getValue() {
			return _value;
		}

		@Override
		public void checkLock() {
			if (!Procedure.isLockedByCurrentThread(_lockId))
				throwIllegalAccess(_table.getTableName(), _key);
		}
	}

	static final class RecordLong<V extends Bean<V>, S extends Safe<V>> implements Rec {
		private final TableLong<V, S> _table;
		private final long _key;
		private final S _value;
		private final int _lockId;

		RecordLong(TableLong<V, S> table, long key, S value) {
			_table = table;
			_key = key;
			_value = value;
			_lockId = table.lockId(key);
		}

		@Override
		public TableBase<V> getTable() {
			return _table;
		}

		@Override
		public Long getKey() {
			return _key;
		}

		@Override
		public long getKeyLong() {
			return _key;
		}

		@Override
		public S getValue() {
			return _value;
		}

		@Override
		public void checkLock() {
			if (!Procedure.isLockedByCurrentThread(_lockId))
				throwIllegalAccess(_table.getTableName(), _key);
		}
	}

	private final ArrayList<Record<?, ?, ?>> _records = new ArrayList<>();
	private final ArrayList<RecordLong<?, ?>> _recordLongs = new ArrayList<>();
	private final ArrayList<Runnable> _onRollbacks = new ArrayList<>();
	private final ArrayList<Runnable> _onCommits = new ArrayList<>();
	private Throwable _illegalException;
	private boolean _hasDirty;

	public static SContext current() {
		return ((ProcThread)Thread.currentThread()).sctx;
	}

	static void throwIllegalAccess(String tableName, Object key) {
		IllegalAccessError error = new IllegalAccessError("access unlocked record! table=" + tableName + ",key=" + key);
		current()._illegalException = error;
		throw error;
	}

	@SuppressWarnings("unchecked")
	static <V, S> S safe(Safe<?> parent, V v) {
		return (S)(v instanceof Bean ? ((Bean<?>)v).safe(parent) : v);
	}

	@SuppressWarnings("unchecked")
	static <V, S> S safeAlone(V v) {
		return (S)(v instanceof Bean ? ((Bean<?>)v).safe(null) : v);
	}

	@SuppressWarnings("unchecked")
	static <V> V unwrap(Object v) {
		return (V)(v instanceof Safe ? ((Safe<?>)v)._bean : v);
	}

	static void checkStoreAll(Object v) {
		if (v instanceof Bean)
			((Bean<?>)v).checkStoreAll();
		else if (v == null)
			throw new NullPointerException();
	}

	static <V> V unstoreAll(V v) {
		if (v instanceof Bean)
			((Bean<?>)v).unstoreAll();
		return v;
	}

	<K, V extends Bean<V>, S extends Safe<V>> S addRecord(Table<K, V, S> table, K key, V value) {
		@SuppressWarnings("unchecked")
		S s = (S)value.safe(null);
		Record<K, V, S> rec = new Record<>(table, key, s);
		s.record(rec);
		_records.add(rec);
		return s;
	}

	<V extends Bean<V>, S extends Safe<V>> S addRecord(TableLong<V, S> table, long key, V value) {
		@SuppressWarnings("unchecked")
		S s = (S)value.safe(null);
		RecordLong<V, S> rec = new RecordLong<>(table, key, s);
		s.record(rec);
		_recordLongs.add(rec);
		return s;
	}

	@SuppressWarnings("unchecked")
	<K, V extends Bean<V>, S extends Safe<V>> S getRecord(Table<K, V, S> table, K key) {
		for (int i = 0, n = _records.size(); i < n; ++i) {
			Record<?, ?, ?> r = _records.get(i);
			if (r.getKey().equals(key) && r.getTable() == table)
				return (S)r.getValue();
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	<V extends Bean<V>, S extends Safe<V>> S getRecord(TableLong<V, S> table, long key) {
		for (int i = 0, n = _recordLongs.size(); i < n; ++i) {
			RecordLong<?, ?> r = _recordLongs.get(i);
			if (r.getKeyLong() == key && r.getTable() == table)
				return (S)r.getValue();
		}
		return null;
	}

	public boolean hasDirty() {
		if (_hasDirty)
			return true;
		for (int i = 0, n = _records.size(); i < n; ++i) {
			if (_records.get(i)._value.isDirty())
				return true;
		}
		for (int i = 0, n = _recordLongs.size(); i < n; ++i) {
			if (_recordLongs.get(i)._value.isDirty())
				return true;
		}
		return false;
	}

	public void addOnCommit(Runnable r) {
		_onCommits.add(r);
	}

	public void addOnRollback(Runnable r) {
		_onRollbacks.add(r);
	}

	void addOnRollbackDirty(Runnable r) {
		_onRollbacks.add(r);
		_hasDirty = true;
	}

	void commit() {
		if (_illegalException != null)
			throw new RuntimeException(_illegalException);
		_onRollbacks.clear();

		int n = _records.size();
		if (n > 0) {
			int i = 0;
			do {
				Record<?, ?, ?> r = _records.get(i);
				if (r._value.isDirty())
					r._table.modify(r._key, r._value._bean);
			}
			while (++i < n);
			_records.clear();
		}

		n = _recordLongs.size();
		if (n > 0) {
			int i = 0;
			do {
				RecordLong<?, ?> r = _recordLongs.get(i);
				if (r._value.isDirty())
					r._table.modify(r._key, r._value._bean);
			}
			while (++i < n);
			_recordLongs.clear();
		}

		n = _onCommits.size();
		if (n > 0) {
			int i = 0;
			do {
				try {
					_onCommits.get(i).run();
				} catch (Throwable e) {
					Log.error("onCommit exception:", e);
				}
			}
			while (++i < n);
			_onCommits.clear();
		}

		_hasDirty = false;
	}

	void rollback() {
		_records.clear();
		_recordLongs.clear();
		_onCommits.clear();

		for (int i = _onRollbacks.size(); --i >= 0; ) {
			try {
				_onRollbacks.get(i).run();
			} catch (Throwable e) {
				Log.error("onRollback exception:", e);
			}
		}
		_onRollbacks.clear();
		_illegalException = null;
		_hasDirty = false;
	}
}
