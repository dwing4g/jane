package jane.test;

import jane.test.net.ActorThread;

@SuppressWarnings("unused")
public final class TestActor extends ActorThread {
	protected TestActor(int id) {
		super("TestActor-" + id);
	}

	private void on(Integer v) {
		System.out.println(Thread.currentThread() + ": " + v);
	}

	private void on(ActorThread.Rpc<Integer> rpc) {
		System.out.println(Thread.currentThread() + ": on Rpc");
		rpc.answer(456);
	}

	public static void main(String[] args) {
		TestActor actor0 = new TestActor(0);
		TestActor actor1 = new TestActor(1);
		actor0.start();
		actor1.start();
		actor0.post(123);
		actor1.ask(new ActorThread.Rpc<Integer>(), actor0, r -> System.out.println(Thread.currentThread() + ": " + r));
	}
}
