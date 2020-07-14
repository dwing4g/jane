package jane.test;

import java.util.HashMap;
import java.util.concurrent.ThreadLocalRandom;
import jane.core.map.IntHashMap;
import jane.core.map.LongHashMap;

public final class TestMap
{
	private static IntHashMap<Long>		  m1;
	private static LongHashMap<Long>	  m2;
	private static HashMap<Integer, Long> m3;
	private static int					  r;

	private static boolean rand()
	{
		return ThreadLocalRandom.current().nextBoolean();
	}

	private static int rand(int n)
	{
		return ThreadLocalRandom.current().nextInt(n);
	}

	@SuppressWarnings("SameParameterValue")
	private static int rand(int m, int n)
	{
		return ThreadLocalRandom.current().nextInt(m, n);
	}

	private static void init(int i)
	{
		m1 = rand() ? new IntHashMap<>() : new IntHashMap<>(rand(1, 64));
		m2 = rand() ? new LongHashMap<>() : new LongHashMap<>(rand(1, 64));
		m3 = new HashMap<>();
		r = i;
	}

	private static void check()
	{
		long[] v1 = new long[1];
		m1.foreach((k, v) -> v1[0] += k * v);
		long[] v2 = new long[1];
		m2.foreach((k, v) -> v2[0] += k * v);
		long[] v3 = new long[1];
		m3.forEach((k, v) -> v3[0] += k * v);
		if (v1[0] != v3[0] || v2[0] != v3[0])
			throw new IllegalStateException();
	}

	private static void put()
	{
		int k = rand(r + 300);
		long v = rand(Integer.MAX_VALUE);
		m1.put(k, v);
		m2.put(k, v);
		m3.put(k, v);
	}

	private static void remove()
	{
		int k = rand(r + 300);
		m1.remove(k);
		m2.remove(k);
		m3.remove(k);
	}

	private static void clear()
	{
		m1.clear();
		m2.clear();
		m3.clear();
	}

	public static void main(String[] args)
	{
		for (int i = 0; i < 1000; ++i)
		{
			init(i);
			for (int j = 0; j < 10000; ++j)
			{
				switch (rand(2))
				{
				case 0:
					put();
					break;
				case 1:
					if (rand(1000) == 0)
						clear();
					else
						remove();
					break;
				}
			}
			check();
		}
		System.out.println("done!");
	}
}
