package limax.edb;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import limax.util.InteractiveTool;

public final class DBTool extends InteractiveTool {
	private final static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.S");

	private DBTool() {
	}

	private Path checkValid(String pathName) {
		Path path = Paths.get(pathName);
		if (!Files.exists(path.resolve("data")) || !Files.exists(path.resolve("log"))) {
			out.println("<" + path.getFileName() + "> not valid database!");
			return null;
		}
		return path;
	}

	private class Recover implements AutoCloseable {
		private final Environment env = new Environment();
		private final LabeledHash hash;
		private final GlobalLogger logger;
		private final List<PageFile> files = new ArrayList<>();
		private final List<Long> ids;

		Recover(final Path path) throws Exception {
			Path dataDir = path.resolve("data");
			Path logDir = path.resolve("log");
			env.setCacheSize(4096);
			hash = new LabeledHash(env, null);
			logger = new GlobalLogger(env, logDir);
			ThreadContext.get().enter(hash);
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir)) {
				for (Path file : stream) {
					PageFile pageFile = new PageFile(file);
					logger.add(pageFile, new PageCache(pageFile, false));
					files.add(pageFile);
				}
			}
			ids = logger.getValidLoggerId();
			ThreadContext.get().leave();
		}

		void listValidLoggerId() throws Exception {
			int n = 0;
			Date date = new Date();
			for (long l : ids) {
				date.setTime(l);
				out.println(n++ + " : " + sdf.format(date));
			}
		}

		void action(int n) throws Exception {
			ThreadContext.get().enter(hash);
			if (ids != null && n >= 0 && n < ids.size()) {
				logger.recover(ids.get(n));
			}
			ThreadContext.get().leave();
		}

		@Override
		public void close() throws Exception {
			for (PageFile pageFile : files) {
				try {
					pageFile.close();
				} catch (IOException e) {
				}
			}
			logger.close();
		}
	}

	public static void main(String[] args) throws Exception {
		new DBTool().interactive(args);
	}

	@Override
	protected void eval(String[] words) throws Exception {
		switch (words[0]) {
		case "rescue":
			if (words.length != 3)
				break;
			Path src = checkValid(words[1]);
			Path dst = Paths.get(words[2]);
			if (src.equals(dst)) {
				out.println("fromPath and toPath is same.");
				break;
			}
			try (Rescue rescue = new Rescue(src, dst)) {
				rescue.run();
			}
			break;
		case "list":
			if (words.length != 3)
				break;
			if (words[1].compareTo("checkpoint") != 0)
				break;
			Path path = checkValid(words[2]);
			if (path == null)
				break;
			try (Recover recover = new Recover(path)) {
				recover.listValidLoggerId();
			}
			break;
		case "recover":
			if (words.length != 4)
				break;
			if (words[1].compareTo("checkpoint") != 0)
				break;
			path = checkValid(words[2]);
			if (path == null)
				break;
			int n = Integer.parseInt(words[3]);
			try (Recover recover = new Recover(path)) {
				recover.action(n);
			}
			break;
		default:
			out.println("rescue <src dbpath> <dst dbpath>");
			out.println("list checkpoint <dbpath>");
			out.println("recover checkpoint <dbpath> <recordNumber>");
			help();
			break;
		}
	}
}
