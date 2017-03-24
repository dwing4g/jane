package jane.tool;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import jane.core.Util;

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

	private ClassReloader()
	{
	}

	public static Instrumentation getInstrumentation()
	{
		return _inst;
	}

	/** @param args */
	public static void premain(String args, Instrumentation inst)
	{
		_inst = inst;
	}

	public static String getClassPathFromData(byte[] classData) throws IOException
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

	public static void reloadClass(byte[] classData) throws Exception, Error
	{
		if(_inst == null)
			throw new NullPointerException("Instrumentation not initialized");
		_inst.redefineClasses(new ClassDefinition(Class.forName(getClassPathFromData(classData)), classData));
	}

	public static void reloadClasses(List<byte[]> classDatas) throws Exception, Error
	{
		if(_inst == null)
			throw new NullPointerException("Instrumentation not initialized");
		int i = 0, n = classDatas.size();
		ClassDefinition[] clsDefs = new ClassDefinition[n];
		for(byte[] classData : classDatas)
			clsDefs[i++] = new ClassDefinition(Class.forName(getClassPathFromData(classData)), classData);
		_inst.redefineClasses(clsDefs);
	}

	public static void reloadClasses(InputStream zipStream) throws Exception, Error
	{
		if(_inst == null)
			throw new NullPointerException("Instrumentation not initialized");
		List<byte[]> classDatas = new ArrayList<>();
		try(ZipInputStream zis = new ZipInputStream(new BufferedInputStream(zipStream)))
		{
			for(ZipEntry ze; (ze = zis.getNextEntry()) != null;)
			{
				if(ze.getName().endsWith(".class"))
				{
					int len = (int)ze.getSize();
					byte[] classData = new byte[len];
					Util.readStream(zis, ze.getName(), classData, len);
					classDatas.add(classData);
				}
			}
		}
		reloadClasses(classDatas);
	}
}
