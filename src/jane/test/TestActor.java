package jane.test;

import jane.test.net.ActorThread;

public final class TestActor extends ActorThread<Object>
{
	@ActorThread.Event(Integer.class)
	private static void doInteger(int v)
	{
		System.out.println(v);
	}

	@Override
	protected void onException(Throwable e)
	{
		e.printStackTrace();
	}

	public static void main(String[] args)
	{
		TestActor actor = new TestActor();
		actor.postCmd(123);
		actor.start();
	}
}
