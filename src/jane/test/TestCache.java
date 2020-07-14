package jane.test;

import jane.core.map.LongConcurrentLRUMap;
import jane.core.map.LongMap;
import jane.core.map.LongMap.LongIterator;
import jane.test.map.ConcurrentLinkedHashMap;

public class TestCache
{
	public static void printMap(LongMap<Integer> m)
	{
		System.out.print('[');
		System.out.print(m.size());
		System.out.print(']');
		for (LongIterator it = m.keyIterator(); it.hasNext();)
		{
			System.out.print(' ');
			System.out.print(it.next());
		}
		System.out.println();
	}

	public static void test(LongMap<Integer> m)
	{
		for (int i = 0; i < 10; ++i)
			m.put(i, i);
		printMap(m);
		m.put(10, 10);
		printMap(m);
		m.put(11, 11);
		printMap(m);
		System.out.println("get(2) = " + m.get(2));
		printMap(m);
		m.put(12, 12);
		printMap(m);
		m.clear();
		printMap(m);
	}

	public static void main(String[] args)
	{
		final int COUNT = 10;

		test(new ConcurrentLinkedHashMap.Builder().concurrencyLevel(1)
				.maximumWeightedCapacity(COUNT).initialCapacity(COUNT).buildLong());
		test(new LongConcurrentLRUMap<>(COUNT, 0.75f, "Test"));
	}
}
