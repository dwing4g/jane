using System;

namespace Jane
{
	/**
	 * RC4加密算法的过滤器;
	 */
	public sealed class RC4Filter
	{
		private readonly byte[] _ctxI = new byte[256];
		private readonly byte[] _ctxO = new byte[256];
		private int _idx1I, _idx2I;
		private int _idx1O, _idx2O;

		private static void SetKey(byte[] ctx, byte[] key, int len)
		{
			for(int i = 0; i < 256; ++i)
				ctx[i] = (byte)i;
			if(len > key.Length) len = key.Length;
			if(len <= 0) return;
			for(int i = 0, j = 0, k = 0; i < 256; ++i)
			{
				k = (k + ctx[i] + key[j]) & 0xff;
				if(++j >= len) j = 0;
				byte t = ctx[i];
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

		private static int Update(byte[] ctx, int idx1, int idx2, byte[] buf, int pos, int len)
		{
			for(int i = 0; i < len; ++i)
			{
				idx1 = (idx1 + 1) & 0xff;
				idx2 = (idx2 + ctx[idx1]) & 0xff;
				byte k = ctx[idx1];
				ctx[idx1] = ctx[idx2];
				ctx[idx2] = k;
				buf[pos + i] ^= ctx[(ctx[idx1] + ctx[idx2]) & 0xff];
			}
			return idx2;
		}

		/**
		 * 加解密一段输入数据;
		 */
		public void UpdateInput(byte[] buf, int pos, int len)
		{
			_idx2I = Update(_ctxI, _idx1I, _idx2I, buf, pos, len);
			_idx1I = (_idx1I + len) & 0xff;
		}

		/**
		 * 加解密一段输出数据;
		 */
		public void UpdateOutput(byte[] buf, int pos, int len)
		{
			_idx2O = Update(_ctxO, _idx1O, _idx2O, buf, pos, len);
			_idx1O = (_idx1O + len) & 0xff;
		}
	}
}
