package jane.test;

import java.util.Arrays;

public class TestSort
{
	static void sort0(int[] v, int n)
	{
		Arrays.sort(v, 0, n);
	}

	static void sort3(int[] v)
	{
		int lockId0 = v[0];
		int lockId1 = v[1];
		int lockId2 = v[2];
		int t;
		//@formatter:off
		t = Math.min(lockId0, lockId1); lockId1 ^= lockId0 ^ t; lockId0 = t;
		t = Math.min(lockId0, lockId2); lockId2 ^= lockId0 ^ t; lockId0 = t;
		t = Math.min(lockId1, lockId2); lockId2 ^= lockId1 ^ t; lockId1 = t;
		//@formatter:on
		v[0] = lockId0;
		v[1] = lockId1;
		v[2] = lockId2;
	}

	static void sort4(int[] v)
	{
		int lockId0 = v[0];
		int lockId1 = v[1];
		int lockId2 = v[2];
		int lockId3 = v[3];
		int t;
		//@formatter:off
		t = Math.min(lockId0, lockId1); lockId1 ^= lockId0 ^ t; lockId0 = t;
		t = Math.min(lockId2, lockId3); lockId3 ^= lockId2 ^ t; lockId2 = t;
		t = Math.min(lockId0, lockId2); lockId2 ^= lockId0 ^ t; lockId0 = t;
		t = Math.min(lockId1, lockId3); lockId3 ^= lockId1 ^ t; lockId1 = t;
		t = Math.min(lockId1, lockId2); lockId2 ^= lockId1 ^ t; lockId1 = t;
		//@formatter:on
		v[0] = lockId0;
		v[1] = lockId1;
		v[2] = lockId2;
		v[3] = lockId3;
	}

	static void sort5(int[] v)
	{
		int lockId0 = v[0];
		int lockId1 = v[1];
		int lockId2 = v[2];
		int lockId3 = v[3];
		int lockId4 = v[4];
		int t;
		//@formatter:off
		t = Math.min(lockId1, lockId2); lockId2 ^= lockId1 ^ t; lockId1 = t;
		t = Math.min(lockId0, lockId2); lockId2 ^= lockId0 ^ t; lockId0 = t;
		t = Math.min(lockId0, lockId1); lockId1 ^= lockId0 ^ t; lockId0 = t;
		t = Math.min(lockId3, lockId4); lockId4 ^= lockId3 ^ t; lockId3 = t;
		t = Math.min(lockId1, lockId4); lockId4 ^= lockId1 ^ t; lockId1 = t;
		t = Math.min(lockId0, lockId3); lockId3 ^= lockId0 ^ t; lockId0 = t;
		t = Math.min(lockId1, lockId3); lockId3 ^= lockId1 ^ t; lockId1 = t;
		t = Math.min(lockId2, lockId4); lockId4 ^= lockId2 ^ t; lockId2 = t;
		t = Math.min(lockId2, lockId3); lockId3 ^= lockId2 ^ t; lockId2 = t;
		//@formatter:on
		v[0] = lockId0;
		v[1] = lockId1;
		v[2] = lockId2;
		v[3] = lockId3;
		v[4] = lockId4;
	}

	static void sort6(int[] v)
	{
		int lockId0 = v[0];
		int lockId1 = v[1];
		int lockId2 = v[2];
		int lockId3 = v[3];
		int lockId4 = v[4];
		int lockId5 = v[5];
		int t;
		//@formatter:off
		t = Math.min(lockId1, lockId2); lockId2 ^= lockId1 ^ t; lockId1 = t;
		t = Math.min(lockId4, lockId5); lockId5 ^= lockId4 ^ t; lockId4 = t;
		t = Math.min(lockId0, lockId2); lockId2 ^= lockId0 ^ t; lockId0 = t;
		t = Math.min(lockId3, lockId5); lockId5 ^= lockId3 ^ t; lockId3 = t;
		t = Math.min(lockId0, lockId1); lockId1 ^= lockId0 ^ t; lockId0 = t;
		t = Math.min(lockId3, lockId4); lockId4 ^= lockId3 ^ t; lockId3 = t;
		t = Math.min(lockId1, lockId4); lockId4 ^= lockId1 ^ t; lockId1 = t;
		t = Math.min(lockId0, lockId3); lockId3 ^= lockId0 ^ t; lockId0 = t;
		t = Math.min(lockId2, lockId5); lockId5 ^= lockId2 ^ t; lockId2 = t;
		t = Math.min(lockId1, lockId3); lockId3 ^= lockId1 ^ t; lockId1 = t;
		t = Math.min(lockId2, lockId4); lockId4 ^= lockId2 ^ t; lockId2 = t;
		t = Math.min(lockId2, lockId3); lockId3 ^= lockId2 ^ t; lockId2 = t;
		//@formatter:on
		v[0] = lockId0;
		v[1] = lockId1;
		v[2] = lockId2;
		v[3] = lockId3;
		v[4] = lockId4;
		v[5] = lockId5;
	}

	private static long seed = System.nanoTime();

	private static long rand()
	{
		final long r = seed * 6364136223846793005L + 1442695040888963407L;
		seed = r;
		return r;
	}

	private static void fillRand(int[] v)
	{
		for (int i = 0, n = v.length; i < n; i++)
			v[0] = (int)rand();
	}

	public static void main(String[] args)
	{
		int[] v0 = new int[6];
		int[] v1 = new int[6];
		int[] v2 = new int[6];
		for (int i = 0; i < 100_000_000; i++)
		{
			fillRand(v0);
			System.arraycopy(v0, 0, v1, 0, v0.length);
			System.arraycopy(v0, 0, v2, 0, v0.length);
			sort0(v1, 3);
			sort3(v2);
			if (!Arrays.equals(v1, v2))
				throw new AssertionError();
			System.arraycopy(v0, 0, v1, 0, v0.length);
			System.arraycopy(v0, 0, v2, 0, v0.length);
			sort0(v1, 4);
			sort4(v2);
			if (!Arrays.equals(v1, v2))
				throw new AssertionError();
			System.arraycopy(v0, 0, v1, 0, v0.length);
			System.arraycopy(v0, 0, v2, 0, v0.length);
			sort0(v1, 5);
			sort5(v2);
			if (!Arrays.equals(v1, v2))
				throw new AssertionError();
			System.arraycopy(v0, 0, v1, 0, v0.length);
			System.arraycopy(v0, 0, v2, 0, v0.length);
			sort0(v1, 6);
			sort6(v2);
			if (!Arrays.equals(v1, v2))
				throw new AssertionError();
		}
		System.out.println("TEST OK!");
	}
}
