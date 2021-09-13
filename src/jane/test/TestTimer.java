package jane.test;

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

		public void doTimer()
		{
			try
			{
				onTimer();
			}
			catch (Exception ignored)
			{
			}
		}

		public abstract void onTimer();
	}

	private static final int SLOT_SHIFT	 = 8;
	private static final int SLOTS_COUNT = 5;
	private static final int SLOT_MASK	 = (1 << SLOT_SHIFT) - 1;
	private static final int SLOT_SIZE	 = (1 << SLOT_SHIFT) * SLOTS_COUNT;

	private final Node[] nodeHeads = new Node[SLOT_SIZE + 1];
	private long		 time;

	public TestTimer(long curTime)
	{
		time = curTime;
	}

	public void add(Node node, long runTime)
	{
		if (node.next != null)
			throw new IllegalStateException("the node is already in timer");
		final int idx;
		final long dt = runTime - time;
		if (dt < (1L << SLOT_SHIFT))
		{
			if (dt < 0)
				idx = runTime < time ? (int)(runTime = time) & SLOT_MASK : SLOT_SIZE;
			else
				idx = (int)runTime & SLOT_MASK;
		}
		else if (dt < (1L << (SLOT_SHIFT * 2)))
			idx = (1 << SLOT_SHIFT) + ((int)(runTime >>> SLOT_SHIFT) & SLOT_MASK);
		else if (dt < (1L << (SLOT_SHIFT * 3)))
			idx = (2 << SLOT_SHIFT) + ((int)(runTime >>> (SLOT_SHIFT * 2)) & SLOT_MASK);
		else if (dt < (1L << (SLOT_SHIFT * 4)))
			idx = (3 << SLOT_SHIFT) + ((int)(runTime >>> (SLOT_SHIFT * 3)) & SLOT_MASK);
		else if (dt < (1L << (SLOT_SHIFT * 5)))
			idx = (4 << SLOT_SHIFT) + ((int)(runTime >>> (SLOT_SHIFT * 4)) & SLOT_MASK);
		else // overflow
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
		long t = time;
		if (curTime < t)
			return;
		for (final Node[] heads = nodeHeads;;)
		{
			int idx = (int)t & SLOT_MASK;
			for (Node head; (head = heads[idx]) != null;)
			{
				heads[idx] = null;
				for (Node node = head.next;;)
				{
					final Node next = node.next;
					node.next = null;
					node.doTimer();
					if (node == head)
						break;
					node = next;
				}
			}
			if (curTime == t)
				return;
			time = ++t;
			long tt = t;
			for (int i = 1; (tt & SLOT_MASK) == 0 && i < SLOTS_COUNT; i++)
			{
				idx = (i << SLOT_SHIFT) + ((int)(tt >>>= SLOT_SHIFT) & SLOT_MASK);
				final Node head = heads[idx];
				if (head != null)
				{
					heads[idx] = null;
					final int base = (i - 1) << SLOT_SHIFT;
					final int shift = (i - 1) * SLOT_SHIFT;
					for (Node node = head.next;;)
					{
						final Node next = node.next;
						idx = base + (int)(node.runTime >>> shift) & SLOT_MASK;
						final Node head0 = heads[idx];
						if (head0 == null)
							node.next = node;
						else
						{
							node.next = head0.next;
							head0.next = node;
						}
						heads[idx] = node;
						if (node == head)
							break;
						node = next;
					}
				}
			}
		}
	}
}
