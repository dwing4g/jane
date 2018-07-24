package jane.core;

import java.lang.ref.WeakReference;

public final class HardReference<T> extends WeakReference<T>
{
	private final T _ref;

	public HardReference(T ref)
	{
		super(null);
		_ref = ref;
	}

	@Override
	public T get()
	{
		return _ref;
	}
}
