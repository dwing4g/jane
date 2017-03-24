package jane.tool;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.util.List;

/*
MANIFEST.MF

Manifest-Version: 1.0
Premain-Class: jane.tool.ClassReloader
Can-Redefine-Classes: true

java ...... -javaagent:lib/jane-core.jar ......

WARNING: Eclipse JDT compiler is not compatible with javac when reloading class
*/
public final class ClassReloader
{
	private static Instrumentation _inst;

	public static Instrumentation getInstrumentation()
	{
		return _inst;
	}

	/** @param args */
	public static void premain(String args, Instrumentation inst)
	{
		_inst = inst;
	}

	private static String getClassPathFromData(byte[] classData) throws IOException
	{
		DataInputStream dis = new DataInputStream(new ByteArrayInputStream(classData));
		dis.readLong(); // skip magic[4] and version[4]
		int constCount = (dis.readShort() & 0xffff) - 1;
		int[] classes = new int[constCount];
		String[] strings = new String[constCount];
		for(int i = 0; i < constCount; ++i)
		{
			int t = dis.read();
			if(t == 7)
				classes[i] = dis.readShort() & 0xffff;
			else if(t == 1)
				strings[i] = dis.readUTF();
			else if(t == 5 || t == 6)
			{
				dis.readLong();
				++i;
			}
			else if(t == 8)
				dis.readShort();
			else
				dis.readInt();
		}
		dis.readShort(); // skip access flags
		return strings[classes[(dis.readShort() & 0xffff) - 1] - 1].replace('/', '.');
	}

	public static void reloadClass(byte[] classData) throws Exception
	{
		if(_inst == null)
			throw new NullPointerException("Instrumentation not initialized");
		Class<?> cls = Class.forName(getClassPathFromData(classData));
		_inst.redefineClasses(new ClassDefinition(cls, classData));
	}

	public static void reloadClasses(List<byte[]> classDatas) throws Exception
	{
		if(_inst == null)
			throw new NullPointerException("Instrumentation not initialized");
		int i = 0, n = classDatas.size();
		ClassDefinition[] clsDefs = new ClassDefinition[n];
		for(byte[] classData : classDatas)
			clsDefs[i++] = new ClassDefinition(Class.forName(getClassPathFromData(classData)), classData);
		_inst.redefineClasses(clsDefs);
	}
}
