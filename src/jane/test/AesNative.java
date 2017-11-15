package jane.test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

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
public final class AesNative
{
	static
	{
		String filename = System.mapLibraryName("aesjni64");
		File file = new File("lib", filename);
		if(!file.exists())
			file = new File(filename);
		System.load(file.getAbsolutePath());
	}

	public static native long aes_create(byte[] keyBuf, int keyPos, byte[] ivBuf, int ivPos, boolean decrypt);

	public static native void aes_encrypt(long handle_aes, byte[] buf, int pos);

	public static native void aes_decrypt(long handle_aes, byte[] buf, int pos);

	public static native void aes_update(long handle_aes, byte[] buf, int pos, int len);

	public static native void aes_destroy(long handle_aes);

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
		for(int i = 0; i < 10000000; ++i)
			methodEncrypt.invoke(aesCrypt, src, 0, src, 0); // support "-XX:+UseAES -XX:+UseAESIntrinsics" optimization
		System.out.println(System.currentTimeMillis() - t);
//		System.out.println(src[0]);
//		System.out.println(src[1]);
//		System.out.println(src[2]);
	}

	public static void testNative()
	{
		long aes = aes_create(new byte[16], 0, new byte[16], 0, false);

		byte[] src = new byte[16];
		long t = System.currentTimeMillis();
		for(int i = 0; i < 10000000; ++i)
			aes_encrypt(aes, src, 0);
		System.out.println(System.currentTimeMillis() - t);
//		System.out.println(src[0]);
//		System.out.println(src[1]);
//		System.out.println(src[2]);

		aes_destroy(aes);
	}

	public static void main(String[] args) throws Exception
	{
		testInternal();
		testNative();
		testInternal();
		testNative();
	}
}
