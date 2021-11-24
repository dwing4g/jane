package jane.test;

import java.util.LinkedHashMap;
import java.util.Map.Entry;
import jane.bean.AllTables;
import jane.bean.TestBean;
import jane.bean.TestType;
import jane.core.DBManager;
import jane.core.Octets;
import jane.core.ProcThread;
import jane.core.Procedure;
import static jane.bean.AllTables.TestTable;

public final class TestIterator {
	public static void main(String[] args) throws Throwable {
		DBManager.instance().startup();
		AllTables.register();
		System.out.println("start");
		final long id = 1;

		Thread pt = new ProcThread(DBManager.instance(), null, () -> {
			new Procedure() {
				@Override
				protected void onProcess() throws Exception {
					TestTable.lockGetOrNew(id, () -> {
						TestType aa = new TestType();
						LinkedHashMap<Octets, TestBean> map = aa.getV18();
						for (int i = 0; i < 256; ++i) {
							Octets o = new Octets();
							o.append((byte)i);
							map.put(o, new TestBean());
						}
						return aa;
					});
				}
			}.run();

			if (args.length == 0) {
				for (int i = 0; i < 1000000; ++i) {
					new Procedure() {
						@Override
						protected void onProcess() throws Exception {
							TestType.Safe a = TestTable.lockGet(id);
							for (Entry<Octets, TestBean.Safe> e : a.getV18().entrySet()) {
								if (e.getValue().getValue1() == 1)
									break;
							}
						}
					}.run();
				}
			} else {
				for (int i = 0; i < 1000000; ++i) {
					new Procedure() {
						@Override
						protected void onProcess() throws Exception {
							TestType.Safe a = TestTable.lockGet(id);
							a.getV18().foreachFilter((k, v) -> v.getValue1() == 1, (k, v) -> false);
						}
					}.run();
				}
			}
		});

		pt.start();
		pt.join();

		System.out.println("checkpoint");
		DBManager.instance().checkpoint();
		System.out.println("end");
		System.exit(0);
	}
}
