package jane.tool;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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
		int constCount = dis.readUnsignedShort() - 1;
		int[] classes = new int[constCount];
		String[] strings = new String[constCount];
		for(int i = 0; i < constCount; ++i)
		{
			int t = dis.read();
			// System.out.println(String.format("%6X: %4d/%4d = %d", classData.length - dis.available(), i + 1, constCount, t));
			switch(t)
			{
				case 1:
					strings[i] = dis.readUTF();
					break;
				case 7:
					classes[i] = dis.readUnsignedShort();
					break;
				default:
					dis.read(); //$FALL-THROUGH$
				case 15:
					dis.read(); //$FALL-THROUGH$
				case 8:
				case 16:
					dis.read();
					dis.read();
					break;
				case 5:
				case 6:
					dis.readLong();
					++i;
					break;
			}
		}
		dis.read(); // skip access flags
		dis.read();
		return strings[classes[dis.readUnsignedShort() - 1] - 1].replace('/', '.');
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

	public static int reloadClasses(ZipFile zipFile) throws Exception, Error
	{
		if(_inst == null)
			throw new NullPointerException("Instrumentation not initialized");
		List<byte[]> classDatas = new ArrayList<>();
		for(Enumeration<? extends ZipEntry> zipEnum = zipFile.entries(); zipEnum.hasMoreElements();)
		{
			ZipEntry ze = zipEnum.nextElement();
			if(ze.isDirectory() || !ze.getName().endsWith(".class")) continue;
			int len = (int)ze.getSize();
			if(len > 0)
			{
				byte[] classData = new byte[len];
				Util.readStream(zipFile.getInputStream(ze), ze.getName(), classData, len);
				classDatas.add(classData);
			}
		}
		reloadClasses(classDatas);
		return classDatas.size();
	}
}
