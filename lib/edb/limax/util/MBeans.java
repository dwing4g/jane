package limax.util;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.management.ObjectName;

public final class MBeans {

	private MBeans() {
	}

	private static Resource root = Resource.createRoot();

	public static Resource register(Resource parent, Object object, String name) {
		return Resource.create(parent, _register(object, name));
	}

	public static Resource root() {
		return root;
	}

	private static Runnable _register(Object object, String name) {
		try {
			AtomicBoolean once = new AtomicBoolean(false);
			ObjectName objname = new ObjectName(name);
			ManagementFactory.getPlatformMBeanServer().registerMBean(object, objname);
			return () -> {
				try {
					if (once.compareAndSet(false, true))
						ManagementFactory.getPlatformMBeanServer().unregisterMBean(objname);
				} catch (Throwable e) {
					if (Trace.isErrorEnabled())
						Trace.error("unregisterMBean name=" + objname, e);
					throw new RuntimeException(e);
				}
			};
		} catch (Exception e) {
			if (Trace.isErrorEnabled())
				Trace.error("registerMBean name=" + name, e);
			throw new RuntimeException(e);
		}
	}
}
