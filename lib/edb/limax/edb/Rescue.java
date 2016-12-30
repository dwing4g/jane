package limax.edb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class Rescue implements AutoCloseable {
	private final static int CACHE_SIZE = 65536;
	private final List<Path> files;
	private final DataBase db;

	public Rescue(final Path src, final Path dst) throws Exception {
		Path dataDir = src.resolve("data");
		try (Stream<Path> stream = Files.list(dataDir)) {
			files = stream.collect(Collectors.toList());
		}
		String[] tableAdd = new String[files.size()];
		for (int i = 0; i < tableAdd.length; i++)
			tableAdd[i] = files.get(i).getFileName().toString();
		Environment env = new Environment();
		env.setCacheSize(CACHE_SIZE);
		db = new DataBase(env, dst);
		db.addTable(tableAdd);
		ThreadContext.get().enterBatchUpdate(db.getLabeledCache());
	}

	@Override
	public void close() throws Exception {
		ThreadContext.get().leaveBatchUpdate();
		db.close();
	}

	private class Update implements QueryData {
		private String tableName;
		private long count = 0;

		Update(Path path) {
			tableName = path.getFileName().toString();
		}

		@Override
		public boolean update(byte[] key, byte[] value) {
			try {
				db.rescue(tableName, key, value);
				db.getLabeledCache().wash();
				count++;
			} catch (IOException e) {
			}
			return true;
		}

		long getCount() {
			return count;
		}
	}

	public void run() {
		for (Path path : files) {
			try (PageBrowser pb = new PageBrowser(path, CACHE_SIZE)) {
				System.out.print("Process <" + path.getFileName() + ">  ");
				Update updater = new Update(path);
				long corrupted = pb.action(updater);
				long count = updater.getCount();
				System.out.println("Count = " + count + " Corrupted = " + corrupted);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
}
