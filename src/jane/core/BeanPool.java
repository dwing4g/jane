package jane.core;

import java.util.ArrayList;
import java.util.List;

/**
 * bean的对象池
 * <p>
 * 目前仅用于生命期可控的数据库的记录值
 */
public final class BeanPool<B extends Bean<B>>
{
	private final List<B> _free_list = new ArrayList<B>();
	private final B       _stub;
	private final int     _max_free_count;

	public BeanPool(B stub, int max_free_count)
	{
		_stub = stub;
		_max_free_count = max_free_count;
	}

	public B alloc()
	{
		synchronized(this)
		{
			int n = _free_list.size();
			if(n > 0) return _free_list.remove(n - 1);
		}
		return _stub.create();
	}

	public synchronized void free(B b)
	{
		if(_free_list.size() < _max_free_count)
		{
			b.reset();
			_free_list.add(b);
		}
	}
}
