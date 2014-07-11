package jane.core;

import java.lang.reflect.Field;

/**
 * 各种基础类型的安全修改类
 */
public abstract class SBase implements Runnable
{
	protected final Bean<?> _bean;
	protected final Field   _field;

	protected SBase(Bean<?> b, Field f)
	{
		_bean = b;
		_field = f;
	}

	public static final class SBoolean extends SBase
	{
		private final boolean _saved;

		public SBoolean(Bean<?> b, Field f, boolean v)
		{
			super(b, f);
			_saved = v;
		}

		@Override
		public void run()
		{
			try
			{
				_field.setBoolean(_bean, _saved);
			}
			catch(Exception e)
			{
				throw new RuntimeException(e);
			}
		}
	}

	public static final class SChar extends SBase
	{
		private final char _saved;

		public SChar(Bean<?> b, Field f, char v)
		{
			super(b, f);
			_saved = v;
		}

		@Override
		public void run()
		{
			try
			{
				_field.setChar(_bean, _saved);
			}
			catch(Exception e)
			{
				throw new RuntimeException(e);
			}
		}
	}

	public static final class SByte extends SBase
	{
		private final byte _saved;

		public SByte(Bean<?> b, Field f, byte v)
		{
			super(b, f);
			_saved = v;
		}

		@Override
		public void run()
		{
			try
			{
				_field.setByte(_bean, _saved);
			}
			catch(Exception e)
			{
				throw new RuntimeException(e);
			}
		}
	}

	public static final class SShort extends SBase
	{
		private final short _saved;

		public SShort(Bean<?> b, Field f, short v)
		{
			super(b, f);
			_saved = v;
		}

		@Override
		public void run()
		{
			try
			{
				_field.setShort(_bean, _saved);
			}
			catch(Exception e)
			{
				throw new RuntimeException(e);
			}
		}
	}

	public static final class SInteger extends SBase
	{
		private final int _saved;

		public SInteger(Bean<?> b, Field f, int v)
		{
			super(b, f);
			_saved = v;
		}

		@Override
		public void run()
		{
			try
			{
				_field.setInt(_bean, _saved);
			}
			catch(Exception e)
			{
				throw new RuntimeException(e);
			}
		}
	}

	public static final class SLong extends SBase
	{
		private final long _saved;

		public SLong(Bean<?> b, Field f, long v)
		{
			super(b, f);
			_saved = v;
		}

		@Override
		public void run()
		{
			try
			{
				_field.setLong(_bean, _saved);
			}
			catch(Exception e)
			{
				throw new RuntimeException(e);
			}
		}
	}

	public static final class SFloat extends SBase
	{
		private final float _saved;

		public SFloat(Bean<?> b, Field f, float v)
		{
			super(b, f);
			_saved = v;
		}

		@Override
		public void run()
		{
			try
			{
				_field.setFloat(_bean, _saved);
			}
			catch(Exception e)
			{
				throw new RuntimeException(e);
			}
		}
	}

	public static final class SDouble extends SBase
	{
		private final double _saved;

		public SDouble(Bean<?> b, Field f, double v)
		{
			super(b, f);
			_saved = v;
		}

		@Override
		public void run()
		{
			try
			{
				_field.setDouble(_bean, _saved);
			}
			catch(Exception e)
			{
				throw new RuntimeException(e);
			}
		}
	}

	public static final class SOctets extends SBase
	{
		private final Octets _saved;

		public SOctets(Bean<?> b, Field f, Octets v, boolean clone)
		{
			super(b, f);
			_saved = (clone ? v.clone() : v);
		}

		@Override
		public void run()
		{
			try
			{
				_field.set(_bean, _saved);
			}
			catch(Exception e)
			{
				throw new RuntimeException(e);
			}
		}
	}

	public static final class SObject extends SBase
	{
		private final Object _saved;

		public SObject(Bean<?> b, Field field, Object v)
		{
			super(b, field);
			_saved = v;
		}

		@Override
		public void run()
		{
			try
			{
				_field.set(_bean, _saved);
			}
			catch(Exception e)
			{
				throw new RuntimeException(e);
			}
		}
	}
}
