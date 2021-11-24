package jane.test;

import java.util.concurrent.CountDownLatch;
import jane.bean.AllTables;
import jane.bean.TestType;
import jane.core.Const;
import jane.core.DBManager;
import jane.core.Procedure;

public final class TestAutoLock {
	public static void main(String[] args) throws Exception {
		DBManager.instance().startup();
		AllTables.register();

		final long k1 = 1;
		final long k2 = 2;
		final int lockMask = Const.lockPoolSize - 1;
		System.out.println("lockId1 = " + (AllTables.TestTable.lockId(k1) & lockMask));
		System.out.println("lockId2 = " + (AllTables.TestTable.lockId(k2) & lockMask));

		System.out.println("begin");
		final CountDownLatch cd1 = new CountDownLatch(2);
		final CountDownLatch cd2 = new CountDownLatch(1);

		DBManager.instance().submit(new Procedure() {
			@Override
			protected void onProcess() throws Exception {
				System.out.println("A: begin");
				TestType.Safe t1 = AllTables.TestTable.lockGet(k1);
				System.out.println("A1: " + (t1 != null ? t1.getV4() : 0));
				Thread.sleep(1000);
				TestType.Safe t2 = AllTables.TestTable.lockGet(k2);
				System.out.println("A2: " + (t2 != null ? t2.getV4() : 0));
				if (t1 == null) {
					t1 = new TestType().safe();
					AllTables.TestTable.put(k1, t1);
				}
				if (t2 == null) {
					t2 = new TestType().safe();
					AllTables.TestTable.put(k2, t2);
				}
				Thread.sleep(1000);
				t1.setV4(t1.getV4() + 1);
				t2.setV4(t2.getV4() + 1);
				System.out.println("A1a: " + t1.getV4());
				System.out.println("A2a: " + t2.getV4());
				System.out.println("A: end");
				cd1.countDown();
			}
		});

		DBManager.instance().submit(new Procedure() {
			@Override
			protected void onProcess() throws Exception {
				System.out.println("B: begin");
				Thread.sleep(500);
				TestType.Safe t2 = AllTables.TestTable.lockGet(k2);
				System.out.println("B2: " + (t2 != null ? t2.getV4() : 0));
				Thread.sleep(1000);
				TestType.Safe t1 = AllTables.TestTable.lockGet(k1);
				System.out.println("B1: " + (t1 != null ? t1.getV4() : 0));
				if (t1 == null) {
					t1 = new TestType().safe();
					AllTables.TestTable.put(k1, t1);
				}
				if (t2 == null) {
					t2 = new TestType().safe();
					AllTables.TestTable.put(k2, t2);
				}
				Thread.sleep(1000);
				t1.setV4(t1.getV4() + 1);
				t2.setV4(t2.getV4() + 1);
				System.out.println("B1a: " + t1.getV4());
				System.out.println("B2a: " + t2.getV4());
				System.out.println("B: end");
				cd1.countDown();
			}
		});

		DBManager.instance().submit(new Procedure() {
			@Override
			protected void onProcess() throws Exception {
				System.out.println("C: begin");
				cd1.await();
				TestType.Safe t1 = AllTables.TestTable.lockGet(k1);
				System.out.println("C1: " + t1.getV4());
				TestType.Safe t2 = AllTables.TestTable.lockGet(k2);
				System.out.println("C2: " + t2.getV4());
				System.out.println("C: end");
				cd2.countDown();
			}
		});

		cd2.await();
		System.out.println("end");
	}
}
