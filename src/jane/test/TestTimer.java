package jane.test;

// java jane.test.TestTimer
// java -Xms100m -Xmx100m -Xmn80m jane.test.TestTimer
// java -XX:+UseParallelGC -Xms100m -Xmx100m -Xmn80m jane.test.TestTimer
// java -XX:+UseShenandoahGC jane.test.TestTimer
// java -XX:+UseZGC jane.test.TestTimer
// java -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC jane.test.TestTimer
public class TestTimer
{
	public static abstract class Node
	{
		private Node next;
		private long runTime;

		public boolean inTimer()
		{
			return next != null;
		}

		public long runTime()
		{
			return runTime;
		}

		public long doTimer()
		{
			try
			{
				return onTimer();
			}
			catch (Exception ignored)
			{
				return 0;
			}
		}

		public long onTimer()
		{
			return 0;
		}
	}

	private static final int SLOT_SHIFT	 = 8;
	private static final int SLOTS_COUNT = 5;
	private static final int SLOT_MASK	 = (1 << SLOT_SHIFT) - 1;
	private static final int SLOT_SIZE	 = (1 << SLOT_SHIFT) * SLOTS_COUNT;

	private final Node[] nodeHeads = new Node[SLOT_SIZE + 1];
	private long		 curTime;

	public TestTimer(long curTime)
	{
		this.curTime = curTime;
	}

	public long curTime()
	{
		return curTime;
	}

	public void add(long runTime, Node node)
	{
		if (node.next != null)
			throw new IllegalStateException("added already");
		final int idx;
		final long dt = runTime - curTime;
		if (dt < 1L << SLOT_SHIFT)
		{
			if (dt < 0)
				idx = runTime < curTime ? (int)(runTime = curTime) & SLOT_MASK : SLOT_SIZE;
			else
				idx = (int)runTime & SLOT_MASK;
		}
		else if (dt < 1L << (SLOT_SHIFT * 2))
			idx = (1 << SLOT_SHIFT) + ((int)(runTime >>> SLOT_SHIFT) & SLOT_MASK);
		else if (dt < 1L << (SLOT_SHIFT * 3))
			idx = (2 << SLOT_SHIFT) + ((int)(runTime >>> (SLOT_SHIFT * 2)) & SLOT_MASK);
		else if (dt < 1L << (SLOT_SHIFT * 4))
			idx = (3 << SLOT_SHIFT) + ((int)(runTime >>> (SLOT_SHIFT * 3)) & SLOT_MASK);
		else if (dt < 1L << (SLOT_SHIFT * 5))
			idx = (4 << SLOT_SHIFT) + ((int)(runTime >>> (SLOT_SHIFT * 4)) & SLOT_MASK);
		else // SLOTS_COUNT overflow
			idx = SLOT_SIZE;
		final Node[] heads = nodeHeads;
		final Node head = heads[idx];
		if (head == null)
			node.next = node;
		else
		{
			node.next = head.next;
			head.next = node;
		}
		heads[idx] = node;
		node.runTime = runTime;
	}

	public void update(long curTime)
	{
		long t = this.curTime;
		if (curTime < t)
			return;
		final Node[] heads = nodeHeads;
		for (int idx = (int)t & SLOT_MASK;;)
		{
			Node head = heads[idx];
			if (head != null)
			{
				this.curTime = t;
				do
				{
					heads[idx] = null;
					for (Node node = head.next;;)
					{
						final Node next = node.next;
						node.next = null;
						final long nextTime = node.doTimer();
						if (nextTime > 0)
							add(t + nextTime, node);
						if (node == head)
							break;
						node = next;
					}
				}
				while ((head = heads[idx]) != null);
			}
			if (t == curTime)
			{
				this.curTime = t;
				return;
			}
			if ((idx = (int)++t & SLOT_MASK) == 0)
			{
				int i = 1;
				while (((t >>> (i * SLOT_SHIFT)) & SLOT_MASK) == 0 && i < SLOTS_COUNT - 1)
					i++;
				for (; i > 0; i--)
				{
					int base = i << SLOT_SHIFT;
					int shift = i * SLOT_SHIFT;
					int idx1 = base + ((int)(t >>> shift) & SLOT_MASK);
					if ((head = heads[idx1]) != null)
					{
						heads[idx1] = null;
						base -= 1 << SLOT_SHIFT;
						shift -= SLOT_SHIFT;
						for (Node node = head.next;;)
						{
							final Node next = node.next;
							idx1 = base + ((int)(node.runTime >>> shift) & SLOT_MASK);
							final Node head1 = heads[idx1];
							if (head1 == null)
								node.next = node;
							else
							{
								node.next = head1.next;
								head1.next = node;
							}
							heads[idx1] = node;
							if (node == head)
								break;
							node = next;
						}
					}
				}
			}
		}
	}

	public static void main(String[] args)
	{
		final long t = System.nanoTime();
		final var state = new Object()
		{
			final TestTimer	timer = new TestTimer((int)rand());
			long			runCount;
			long			seed;

			long rand()
			{
				final long r = seed * 6364136223846793005L + 1442695040888963407L;
				seed = r;
				return r;
			}
		};
		final TestTimer timer = state.timer;
		final long curTime = timer.curTime();
		for (int i = 0; i < 100_000; i++)
		{
			timer.add(curTime + (state.rand() & 0x1_ffff), new Node()
			{
				@Override
				public long onTimer()
				{
					final long runTime = runTime();
					final long curTime = state.timer.curTime();
					if (runTime != curTime)
						throw new AssertionError(String.format("0x%X != 0x%X", runTime, curTime));
					state.runCount++;
					return state.rand() & 0x1_ffff;
				}
			});
		}
		for (int i = 0; i < 86_400_000; i += 33)
			timer.update(curTime + i);
		System.out.println(state.runCount + ", " + (System.nanoTime() - t) / 1_000_000 + " ms");
	}
}
