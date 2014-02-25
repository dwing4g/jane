using System;

namespace jane
{
	/**
	 * RC4加密算法的过滤器;
	 */
	public sealed class RC4Filter
	{
		private readonly byte[] _ctx_i = new byte[256];
		private readonly byte[] _ctx_o = new byte[256];
		private int _idx1_i, _idx2_i;
		private int _idx1_o, _idx2_o;

		private static void setKey(byte[] ctx, byte[] key, int len)
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
		public void setInputKey(byte[] key, int len)
		{
			setKey(_ctx_i, key, len);
			_idx1_i = _idx2_i = 0;
		}

		/**
		 * 设置网络输出流的对称密钥;
		 */
		public void setOutputKey(byte[] key, int len)
		{
			setKey(_ctx_o, key, len);
			_idx1_o = _idx2_o = 0;
		}

		private static int update(byte[] ctx, int idx1, int idx2, byte[] buf, int pos, int len)
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
		public void updateInput(byte[] buf, int pos, int len)
		{
			_idx2_i = update(_ctx_i, _idx1_i, _idx2_i, buf, pos, len);
			_idx1_i = (_idx1_i + len) & 0xff;
		}

		/**
		 * 加解密一段输出数据;
		 */
		public void updateOutput(byte[] buf, int pos, int len)
		{
			_idx2_o = update(_ctx_o, _idx1_o, _idx2_o, buf, pos, len);
			_idx1_o = (_idx1_o + len) & 0xff;
		}
	}
}
