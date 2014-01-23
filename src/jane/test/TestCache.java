package jane.test;

import java.util.Map;
import jane.core.Util;

public class TestCache
{
	public static void printMap(Map<Integer, Integer> m)
	{
		System.out.print('[');
		System.out.print(m.size());
		System.out.print(']');
		for(int i : m.keySet())
		{
			System.out.print(' ');
			System.out.print(i);
		}
		System.out.println();
	}

	public static void main(String[] args)
	{
		Map<Integer, Integer> m = Util.newLRUConcurrentHashMap(10);
		for(int i = 0; i < 10; ++i)
			m.put(i, i);
		printMap(m);
		m.put(10, 10);
		printMap(m);
		m.put(11, 11);
		printMap(m);
		assert m.get(2) == 2 : "get error";
		printMap(m);
		m.put(12, 12);
		printMap(m);
	}
}
