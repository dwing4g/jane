package limax.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;

public abstract class InteractiveTool {
	protected PrintStream out = System.out;

	protected abstract void eval(String[] words) throws Exception;

	protected void help() {
		out.println("out <filename> <charset> #default System.out UTF-8");
		out.println("exit");
		out.println("quit");
	}

	protected void interactive(String[] args) throws Exception {
		String cmds = null;
		if (args.length > 1) {
			if (args[0].equals("-e")) {
				cmds = args[1];
				if (cmds.startsWith("\"") && cmds.endsWith("\""))
					cmds = cmds.substring(1, cmds.length() - 1);
			}
		}
		if (cmds != null) {
			for (String cmd : cmds.split(";")) {
				String[] words = cmd.toLowerCase().split("\\s+");
				eval(words);
			}
		} else {
			BufferedReader cons = new BufferedReader(new InputStreamReader(System.in));
			String line;
			System.out.print("#");
			while ((line = cons.readLine()) != null) {
				String[] words = line.toLowerCase().split("\\s+");
				if (words.length == 0)
					continue;
				switch (words[0]) {
				case "exit":
				case "quit": {
					out.flush();
					return;
				}
				case "out":
					if (words.length == 1) {
						out = System.out;
					} else if (words.length == 2) {
						out = new PrintStream(words[1], "UTF-8");
					} else if (words.length == 3) {
						out = new PrintStream(words[1], words[2]);
					} else {
						out.println("out [filename [charset]] #default System.out UTF-8");
					}
					break;
				default:
					if (!words[0].isEmpty())
						eval(words);
				}
				System.out.print("#");
			}
		}
	}

}
