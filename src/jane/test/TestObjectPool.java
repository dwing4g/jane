package jane.test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** 简单无锁的对象池分配器. 基于侵入式链表栈 */
public final class TestObjectPool<T extends TestObjectPool.Node<T>> {
	public interface Node<T> {
		T getNext();

		void setNext(T next);
	}

	private final AtomicReference<T> head = new AtomicReference<>();
	private final Supplier<T> factory;

	public TestObjectPool(Supplier<T> factory) {
		this.factory = factory;
	}

	public T alloc() {
		for (; ; ) {
			T obj = head.get();
			if (obj == null)
				return factory.get();
			T next = obj.getNext();
			if (head.compareAndSet(obj, next)) {
				obj.setNext(null);
				return obj;
			}
		}
	}

	public void free(T obj) {
		for (; ; ) {
			T next = head.get();
			obj.setNext(next);
			if (head.compareAndSet(next, obj))
				break;
		}
	}
}
