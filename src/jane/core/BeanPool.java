package jane.core;

import java.util.ArrayList;
import java.util.List;

/**
 * bean的对象池
 * <p>
 * 目前仅用于实验
 */
public final class BeanPool<B extends Bean<B>>
{
	private final List<B> _freeList = new ArrayList<>();
	private final B       _stub;
	private final int     _maxFreeCount;

	public BeanPool(B stub, int maxFreeCount)
	{
		_stub = stub;
		_maxFreeCount = maxFreeCount;
	}

	public B alloc()
	{
		synchronized(this)
		{
			int n = _freeList.size();
			if(n > 0) return _freeList.remove(n - 1);
		}
		return _stub.create();
	}

	public void free(B b)
	{
		b.reset();
		synchronized(this)
		{
			if(_freeList.size() < _maxFreeCount)
			    _freeList.add(b);
		}
	}
}
