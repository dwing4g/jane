package limax.util;

import java.util.Arrays;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;

class ThreadPoolExecutorMBean implements DynamicMBean {
	private final ThreadPoolExecutor executor;
	private final Resource resource;
	private final MBeanInfo info;

	ThreadPoolExecutorMBean(String name, ThreadPoolExecutor executor) {
		this.executor = executor;
		MBeanAttributeInfo[] attrinfo = {
				new MBeanAttributeInfo("TaskCount", "java.lang.Long", "TaskCount", true, false, false),
				new MBeanAttributeInfo("CompletedTaskCount", "java.lang.Long", "CompletedTaskCount", true, false,
						false),
				new MBeanAttributeInfo("ActiveCount", "java.lang.Integer", "ActiveCount", true, false, false),
				new MBeanAttributeInfo("PoolSize", "java.lang.Integer", "PoolSize", true, false, false),
				new MBeanAttributeInfo("CorePoolSize", "java.lang.Integer", "CorePoolSize", true, true, false),
				new MBeanAttributeInfo("MaximumPoolSize", "java.lang.Integer", "MaximumPoolSize", true, true, false),
				new MBeanAttributeInfo("LargestPoolSize", "java.lang.Integer", "LargestPoolSize", true, false, false),
				new MBeanAttributeInfo("KeepAliveTime", "java.lang.Long", "KeepAliveTime", true, true, false), };
		this.info = new MBeanInfo(getClass().getName(), name, attrinfo, null, null, null);
		this.resource = MBeans.register(MBeans.root(), this, "limax.util:type=ConcurrentEnvironment,name=" + name);
	}

	void close() {
		resource.close();
	}

	ThreadPoolExecutor getThreadPoolExecutor() {
		return executor;
	}

	@Override
	public Object getAttribute(String attribute) {
		switch (attribute) {
		case "TaskCount":
			return executor.getTaskCount();
		case "CompletedTaskCount":
			return executor.getCompletedTaskCount();
		case "ActiveCount":
			return executor.getActiveCount();
		case "PoolSize":
			return executor.getPoolSize();
		case "CorePoolSize":
			return executor.getCorePoolSize();
		case "MaximumPoolSize":
			return executor.getMaximumPoolSize();
		case "LargestPoolSize":
			return executor.getLargestPoolSize();
		case "KeepAliveTime":
			return executor.getKeepAliveTime(TimeUnit.MILLISECONDS);
		}
		return null;
	}

	@Override
	public void setAttribute(Attribute attribute) {
		switch (attribute.getName()) {
		case "CorePoolSize":
			executor.setCorePoolSize((Integer) attribute.getValue());
			break;
		case "KeepAliveTime":
			executor.setMaximumPoolSize((Integer) attribute.getValue());
			break;
		case "MaximumPoolSize":
			executor.setMaximumPoolSize((Integer) attribute.getValue());
			break;
		}
	}

	@Override
	public AttributeList getAttributes(String[] attributes) {
		return new AttributeList(
				Arrays.stream(attributes).map(a -> new Attribute(a, getAttribute(a))).collect(Collectors.toList()));
	}

	@Override
	public AttributeList setAttributes(AttributeList attributes) {
		attributes.asList().stream().forEach(a -> setAttribute(a));
		return attributes;
	}

	@Override
	public Object invoke(String actionName, Object[] params, String[] signature) {
		return null;
	}

	@Override
	public MBeanInfo getMBeanInfo() {
		return info;
	}

}
