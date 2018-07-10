package jane.test;

import jane.test.net.ActorThread;

public final class TestActor extends ActorThread<Object>
{
	protected TestActor()
	{
		super("TestActorThread");
	}

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
		actor.postMsg(123);
		actor.start();
	}
}
