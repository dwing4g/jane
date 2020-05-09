package jane.test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

public final class TestCalPerf
{
	private static final ThreadLocalRandom r		  = ThreadLocalRandom.current();
	private static final int			   TEST_COUNT = 100_000_000;

	public static int test1_Int1_Add()
	{
		int v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v += r.nextInt() | 1;
		return v;
	}

	public static int test1_Int2_Sub()
	{
		int v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v -= r.nextInt() | 1;
		return v;
	}

	public static int test1_Int3_Mul()
	{
		int v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v *= r.nextInt() | 1;
		return v;
	}

	public static int test1_Int4_Div()
	{
		int v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v /= r.nextInt() | 1;
		return v;
	}

	public static int test1_Int5_Mod()
	{
		int v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v %= r.nextInt() | 1;
		return v;
	}

	public static int test1_Int6_Xor()
	{
		int v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v ^= r.nextInt() | 1;
		return v;
	}

	public static long test2_Long1_Add()
	{
		long v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v += r.nextLong() | 1;
		return v;
	}

	public static long test2_Long2_Sub()
	{
		long v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v -= r.nextLong() | 1;
		return v;
	}

	public static long test2_Long3_Mul()
	{
		long v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v *= r.nextLong() | 1;
		return v;
	}

	public static long test2_Long4_Div()
	{
		long v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v /= r.nextLong() | 1;
		return v;
	}

	public static long test2_Long5_Mod()
	{
		long v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v %= r.nextLong() | 1;
		return v;
	}

	public static long test2_Long6_Xor()
	{
		long v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v ^= r.nextLong() | 1;
		return v;
	}

	public static float test3_Float1_Add()
	{
		float v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v += r.nextFloat();
		return v;
	}

	public static float test3_Float2_Sub()
	{
		float v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v -= r.nextFloat();
		return v;
	}

	public static float test3_Float3_Mul()
	{
		float v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v *= r.nextFloat();
		return v;
	}

	public static float test3_Float4_Div()
	{
		float v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v /= r.nextFloat();
		return v;
	}

	public static float test3_Float5_Mod()
	{
		float v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v %= r.nextFloat();
		return v;
	}

	public static double test4_Double1_Add()
	{
		double v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v += r.nextDouble();
		return v;
	}

	public static double test4_Double2_Sub()
	{
		double v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v -= r.nextDouble();
		return v;
	}

	public static double test4_Double3_Mul()
	{
		double v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v *= r.nextDouble();
		return v;
	}

	public static double test4_Double4_Div()
	{
		double v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v /= r.nextDouble();
		return v;
	}

	public static double test4_Double5_Mod()
	{
		double v = 0;
		for (int i = 0; i < TEST_COUNT; ++i, ++v)
			v %= r.nextDouble();
		return v;
	}

	public static void doAll(boolean doPrint) throws ReflectiveOperationException
	{
		int v = 0;
		Method[] methods = TestCalPerf.class.getMethods();
		Arrays.sort(methods, (a, b) -> a.getName().compareTo(b.getName()));
		for (Method method : methods)
		{
			String name = method.getName();
			if (name.startsWith("test"))
			{
				long t = System.nanoTime();
				v += ((Number)method.invoke(null)).intValue();
				t = System.nanoTime() - t;
				if (doPrint)
					System.out.println(name + ": " + t / 1_000_000 + " ms");
			}
		}
		if (doPrint)
			System.out.println("result: " + v); // avoid dead code eliminating
	}

	public static void main(String[] args) throws ReflectiveOperationException
	{
		for (int i = 0; i < 5; ++i)
		{
			System.out.println("warming up ... " + i);
			doAll(false);
		}
		doAll(true);
	}
}
