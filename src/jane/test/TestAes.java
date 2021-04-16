package jane.test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 10M times block encrypt (160MB) benchmark
 * win,i5-4430(3GHz):
 *   32-bit pure asm:     656ms
 *   64-bit pure asm:     562ms
 *   64-bit aes-ni:       265ms
 *   64-bit aes-ni+jni:   710ms
 *   64-bit jdk-internal: 270ms
 *   64-bit jdk-ecb:      457ms
 *   64-bit jdk-ctr:      965ms (stream)
 *   64-bit pure java rc4:509ms (stream)
 * linux,Xeon-E2630(2.2GHz)
 *   64-bit aes-ni:       253ms
 */
public final class TestAes
{
	static
	{
		String filename = System.mapLibraryName("aesjni64");
		File file = new File("lib", filename);
		if (!file.exists())
			file = new File(filename);
		System.load(file.getAbsolutePath());
	}

	public static native long aes_create(byte[] keyBuf, int keyPos, byte[] ivBuf, int ivPos, boolean decrypt);

	public static native void aes_encrypt(long handle_aes, byte[] buf, int pos);

	public static native void aes_decrypt(long handle_aes, byte[] buf, int pos);

	public static native void aes_update(long handle_aes, byte[] buf, int pos, int len);

	public static native void aes_destroy(long handle_aes);

	public static void testAesEcb() throws Exception
	{
		Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
		aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[16], "AES"));
		byte[] src = new byte[16];
		long t = System.currentTimeMillis();
		for (int i = 0; i < 10000000; ++i)
			aes.update(src, 0, src.length, src, 0);
		System.out.println(System.currentTimeMillis() - t);
	}

	public static void testAesCtr() throws Exception
	{
		Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
		aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[16], "AES"), new IvParameterSpec(new byte[16]));
		byte[] src = new byte[16];
		long t = System.currentTimeMillis();
		for (int i = 0; i < 10000000; ++i)
			aes.update(src, 0, src.length, src, 0);
		System.out.println(System.currentTimeMillis() - t);
	}

	public static void testInternal() throws Exception
	{
		Class<?> clsAESCrypt = Class.forName("com.sun.crypto.provider.AESCrypt");
		Constructor<?> cons = clsAESCrypt.getDeclaredConstructor();
		Method methodInit = clsAESCrypt.getDeclaredMethod("init", boolean.class, String.class, byte[].class);
		Method methodEncrypt = clsAESCrypt.getDeclaredMethod("encryptBlock", byte[].class, int.class, byte[].class, int.class);
		cons.setAccessible(true);
		methodInit.setAccessible(true);
		methodEncrypt.setAccessible(true);

		Object aesCrypt = cons.newInstance();
		methodInit.invoke(aesCrypt, false, "AES", new byte[16]);
		byte[] src = new byte[16];
		long t = System.currentTimeMillis();
		for (int i = 0; i < 10000000; ++i)
			methodEncrypt.invoke(aesCrypt, src, 0, src, 0); // support "-XX:+UseAES -XX:+UseAESIntrinsics" optimization
		System.out.println(System.currentTimeMillis() - t);
//		System.out.println(src[0]);
//		System.out.println(src[1]);
//		System.out.println(src[2]);
	}

	public static Object[] testInternalMem() throws Exception // 280B/AESCrypt
	{
		Class<?> clsAESCrypt = Class.forName("com.sun.crypto.provider.AESCrypt");
		Constructor<?> cons = clsAESCrypt.getDeclaredConstructor();
		Method methodInit = clsAESCrypt.getDeclaredMethod("init", boolean.class, String.class, byte[].class);
		Method methodEncrypt = clsAESCrypt.getDeclaredMethod("encryptBlock", byte[].class, int.class, byte[].class, int.class);
		cons.setAccessible(true);
		methodInit.setAccessible(true);
		methodEncrypt.setAccessible(true);

		byte[] src = new byte[16];
		Object[] holds = new Object[100000];
		for (int i = 0; i < holds.length; ++i)
		{
			holds[i] = cons.newInstance();
			methodInit.invoke(holds[i], false, "AES", new byte[16]);
			methodEncrypt.invoke(holds[i], src, 0, src, 0);
		}

		Thread.sleep(100000000);
		return holds;
	}

	public static void testInternalStream() throws Exception
	{
		Class<?> clsAESCrypt = Class.forName("com.sun.crypto.provider.AESCrypt");
		Constructor<?> cons = clsAESCrypt.getDeclaredConstructor();
		Method methodInit = clsAESCrypt.getDeclaredMethod("init", boolean.class, String.class, byte[].class);
		Method methodEncrypt = clsAESCrypt.getDeclaredMethod("encryptBlock", byte[].class, int.class, byte[].class, int.class);
		cons.setAccessible(true);
		methodInit.setAccessible(true);
		methodEncrypt.setAccessible(true);

		Object aesCrypt = cons.newInstance();
		methodInit.invoke(aesCrypt, false, "AES", new byte[16]);
		byte[] src = new byte[16];
		//noinspection MismatchedReadAndWriteOfArray
		byte[] dst = new byte[16];
		long t = System.currentTimeMillis();
		for (int i = 0; i < 10000000; ++i)
		{
			methodEncrypt.invoke(aesCrypt, src, 0, src, 0);
			for (int j = 0; j < 16; ++j)
				dst[j] ^= src[j];
		}
		System.out.println(System.currentTimeMillis() - t);
	}

	public static void testNative()
	{
		long aes = aes_create(new byte[16], 0, new byte[16], 0, false);

		byte[] src = new byte[16];
		long t = System.currentTimeMillis();
		for (int i = 0; i < 10000000; ++i)
			aes_encrypt(aes, src, 0);
		System.out.println(System.currentTimeMillis() - t);
//		System.out.println(src[0]);
//		System.out.println(src[1]);
//		System.out.println(src[2]);

		aes_destroy(aes);
	}

	public static void testRc4()
	{
		TestRc4Filter rc4 = new TestRc4Filter();
		rc4.setInputKey(new byte[16], 16);
		byte[] src = new byte[16];
		long t = System.currentTimeMillis();
		for (int i = 0; i < 10000000; ++i)
			rc4.updateInput(src, 0, src.length);
		System.out.println(System.currentTimeMillis() - t);
	}

	public static void main(String[] args) throws Exception
	{
//		testInternalMem();
		testAesEcb();
		testAesCtr();
		testInternal();
		testInternalStream();
		testNative();
		testRc4();
		System.out.println();
		testAesEcb();
		testAesCtr();
		testInternal();
		testInternalStream();
		testNative();
		testRc4();
	}
}
