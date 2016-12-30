package limax.edb;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

final class PageFile implements AutoCloseable {
	private final static Set<StandardOpenOption> options = EnumSet.of(StandardOpenOption.CREATE,
			StandardOpenOption.READ, StandardOpenOption.WRITE);
	private volatile FileChannel channel;
	private final Path path;
	private final UUID uuid;

	PageFile(Path path) throws IOException {
		this.path = path;
		this.channel = FileChannel.open(path, options);
		this.uuid = UUID.nameUUIDFromBytes(path.getFileName().toString().getBytes());
	}

	void read(long index, PageLayout layout) throws IOException {
		for (;;) {
			FileChannel fc = channel;
			try {
				layout.data().clear();
				int n = fc.read(layout.data(), index * Configure.PAGESIZE);
				if (n != Configure.PAGESIZE)
					throw new IOException(path + " read page " + index + " bad size = " + n);
				layout.data().flip();
				return;
			} catch (ClosedByInterruptException e) {
				synchronized (path) {
					if (channel == fc)
						channel = FileChannel.open(path, options);
				}
			}
		}
	}

	void write(long index, PageLayout layout) throws IOException {
		for (;;) {
			FileChannel fc = channel;
			try {
				layout.data().rewind();
				int n = fc.write(layout.data(), index * Configure.PAGESIZE);
				if (n != Configure.PAGESIZE)
					throw new IOException(path + " write page " + index + " bad size = " + n);
				return;
			} catch (ClosedByInterruptException e) {
				synchronized (path) {
					if (channel == fc)
						channel = FileChannel.open(path, options);
				}
			}
		}
	}

	void sync() throws IOException {
		channel.force(false);
	}

	long size() throws IOException {
		return channel.size();
	}

	UUID getUUID() {
		return uuid;
	}

	Path getPath() {
		return path;
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}
}
