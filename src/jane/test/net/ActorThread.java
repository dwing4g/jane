package jane.test.net;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public abstract class ActorThread extends Thread {
	private static final ScheduledThreadPoolExecutor delayExecutor;
	private final ConcurrentLinkedQueue<Object> msgQueue = new ConcurrentLinkedQueue<>();
	private final HashMap<Class<?>, MethodHandle> dispatcher = new HashMap<>();
	protected long lastMsgTimeBegin;

	static {
		delayExecutor = new ScheduledThreadPoolExecutor(1, r -> {
			Thread t = new Thread(r, "ActorDelayThread");
			t.setDaemon(true);
			return t;
		});
	}

	public static int getDelayQueueSize() {
		return delayExecutor.getQueue().size();
	}

	protected ActorThread(String threadName) {
		super(threadName);
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		for (Class<?> cls = getClass(); cls != null; cls = cls.getSuperclass()) {
			for (Method method : cls.getDeclaredMethods()) {
				if (method.getName().equals("on") && method.getParameterCount() == 1) {
					method.setAccessible(true);
					try {
						dispatcher.putIfAbsent(method.getParameterTypes()[0], lookup.unreflect(method));
					} catch (IllegalAccessException ignored) {
					}
				}
			}
		}
	}

	protected void on(Object msg) {
		System.err.println("unknown msg: " + msg);
	}

	protected void onIdle() throws InterruptedException {
		long curTime = System.currentTimeMillis();
		long sleepTime = 100 - (curTime - lastMsgTimeBegin);
		if (sleepTime > 0) {
			Thread.sleep(sleepTime);
			curTime = System.currentTimeMillis();
		}
		lastMsgTimeBegin = curTime;
	}

	protected boolean onException(Throwable e) {
		if (e instanceof InterruptedException) {
			//noinspection ResultOfMethodCallIgnored
			interrupted(); // clear status
			return false;
		}
		e.printStackTrace();
		return true;
	}

	public int getMsgQueueSize() {
		return msgQueue.size();
	}

	public void post(Object msg) {
		if (msg != null)
			msgQueue.offer(msg);
	}

	public void postDelay(long delayMs, Object msg) {
		if (delayMs <= 0)
			post(msg);
		else if (msg != null)
			delayExecutor.schedule(() -> post(msg), delayMs, TimeUnit.MILLISECONDS);
	}

	public static class Rpc<R> {
		ActorThread caller;
		R result;
		Consumer<R> onResult;

		public void answer(R r) {
			result = r;
			ActorThread caller = this.caller;
			if (caller != null) {
				this.caller = null;
				if (onResult != null)
					caller.msgQueue.offer(this);
			}
		}

		void onResult() {
			onResult.accept(result);
		}
	}

	public <R, T extends Rpc<R>> void ask(T msg, Consumer<R> onResult) {
		ask(msg, (ActorThread)currentThread(), onResult);
	}

	public <R, T extends Rpc<R>> void ask(T msg, ActorThread caller, Consumer<R> onResult) {
		if (caller == null)
			throw new NullPointerException("caller");
		if (msg != null) {
			msg.caller = caller;
			msg.onResult = onResult;
			msgQueue.offer(msg);
		}
	}

	@Override
	public void run() {
		if (Thread.currentThread() != this || lastMsgTimeBegin != 0)
			throw new IllegalStateException();
		for (lastMsgTimeBegin = System.currentTimeMillis(); ; ) {
			try {
				for (Object msg; (msg = msgQueue.poll()) != null; ) {
					Class<?> msgCls = msg.getClass();
					MethodHandle mh = dispatcher.get(msgCls);
					if (mh == null) {
						Class<?> cls = msgCls;
						do
							cls = cls.getSuperclass();
						while ((mh = dispatcher.get(cls)) == null);
						dispatcher.put(msgCls, mh);
					}
					if (msg instanceof Rpc) {
						Rpc<?> rpcMsg = (Rpc<?>)msg;
						if (rpcMsg.caller == null) {
							rpcMsg.onResult();
							continue;
						}
					}
					mh.invoke(this, msg);
				}
				onIdle();
			} catch (Throwable e) {
				if (!onException(e))
					break;
			}
		}
	}
}
