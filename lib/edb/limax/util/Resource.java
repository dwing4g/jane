package limax.util;

import java.util.Collection;
import java.util.HashSet;

public class Resource {
	private final Resource parent;
	private final Collection<Resource> children = new HashSet<Resource>();
	private Runnable cleanup;

	private Resource() {
		this.parent = null;
		this.cleanup = new Runnable() {
			@Override
			public void run() {
			}
		};
	}

	private Resource(Resource parent, Runnable cleanup) {
		this.parent = parent;
		this.cleanup = cleanup;
		synchronized (parent) {
			if (parent.cleanup == null) {
				cleanup.run();
				throw new IllegalStateException();
			}
			parent.children.add(this);
		}
	}

	private synchronized void _close() {
		if (cleanup == null)
			return;
		for (Resource c : children)
			c._close();
		children.clear();
		cleanup.run();
		cleanup = null;
	}

	public void close() {
		if (parent != null)
			synchronized (parent) {
				if (parent.children.remove(this))
					_close();
			}
		else
			_close();
	}

	public static Resource createRoot() {
		return new Resource();
	}

	public static Resource create(Resource parent, Runnable cleanup) {
		if (cleanup == null)
			throw new NullPointerException();
		if (parent == null) {
			cleanup.run();
			throw new NullPointerException();
		}
		return new Resource(parent, cleanup);
	}
}
