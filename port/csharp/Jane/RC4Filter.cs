using System;

namespace Jane
{
	/**
	 * RC4加密算法的过滤器;
	 */
	public sealed class RC4Filter
	{
		readonly byte[] _ctxI = new byte[256];
		readonly byte[] _ctxO = new byte[256];
		int _idx1I, _idx2I;
		int _idx1O, _idx2O;

		static void SetKey(byte[] ctx, byte[] key, int len)
		{
			for(int i = 0; i < 256; ++i)
				ctx[i] = (byte)i;
			if(len > key.Length) len = key.Length;
			if(len <= 0) return;
			for(int i = 0, j = 0, k = 0; i < 256; ++i)
			{
				byte t = ctx[i];
				k = (k + t + key[j]) & 0xff;
				if(++j >= len) j = 0;
				ctx[i] = ctx[k];
				ctx[k] = t;
			}
		}

		/**
		 * 设置网络输入流的对称密钥;
		 */
		public void SetInputKey(byte[] key, int len)
		{
			SetKey(_ctxI, key, len);
			_idx1I = _idx2I = 0;
		}

		/**
		 * 设置网络输出流的对称密钥;
		 */
		public void SetOutputKey(byte[] key, int len)
		{
			SetKey(_ctxO, key, len);
			_idx1O = _idx2O = 0;
		}

		static int Update(byte[] ctx, int idx1, int idx2, byte[] buf, int pos, int len)
		{
			for(len += pos; pos < len; ++pos)
			{
				idx1 = (idx1 + 1) & 0xff;
				byte a = ctx[idx1];
				idx2 = (idx2 + a) & 0xff;
				byte b = ctx[idx2];
				ctx[idx1] = b;
				ctx[idx2] = a;
				buf[pos] ^= ctx[(a + b) & 0xff];
			}
			return idx2;
		}

		/**
		 * 加解密一段输入数据;
		 */
		public void UpdateInput(byte[] buf, int pos, int len)
		{
			_idx2I = Update(_ctxI, _idx1I, _idx2I, buf, pos, len);
			_idx1I += len;
		}

		/**
		 * 加解密一段输出数据;
		 */
		public void UpdateOutput(byte[] buf, int pos, int len)
		{
			_idx2O = Update(_ctxO, _idx1O, _idx2O, buf, pos, len);
			_idx1O += len;
		}
	}
}
