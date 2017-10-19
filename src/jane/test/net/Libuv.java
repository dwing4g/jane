package jane.test.net;

import java.nio.ByteBuffer;

/**
 * 基于libuv本地库的TCP网络处理
 * <p>
 * libuv的特点是单线程的事件处理循环,异步无阻塞,事件响应式,极其优化的底层IO实现(使用epoll,kqueue,IOCP),极少的内存开销<br>
 * 由于一个处理循环只绑定到一个线程,一个端口监听到的所有连接只能绑定监听所在的循环,所以一个端口的大规模连接可能会是瓶颈,即使底层IO效率极高<br>
 * 如果仍然需要利用libuv,可以考虑把复杂耗时的回调处理移到其它线程执行(会带来线程间通信开销和延迟),或者分多端口监听,每个端口使用不同的处理循环和线程<br>
 * 由于几乎没有内存分配开销,接收数据零拷贝,所以接收的数据需要即时处理或复制出来,另外为了保持接口简单,发送数据接口也要排队调用,成功回调前不能多次发送<br>
 * 通过单纯的单线程echo的性能测试表明libuv性能是mina,aio,netty等使用NIO/AIO的1.5倍,几乎没有内存分配开销<br>
 * 需要加载本地库(https://github.com/dwing4g/libuv): uvjni{32|64}.dll,libuvjni{32|64}.so<br>
 */
public final class Libuv
{
	/**
	 * libuv的处理循环回调. 仅在libuv_loop_run的调用中回调
	 */
	public interface LibuvLoopHandler
	{
		/**
		 * <li>调用libuv_tcp_bind后会持续产生此回调, 每次表示一个被动连接建立成功;
		 * <li>调用libuv_tcp_connect后一定会产生一次此回调, 表示主动连接建立成功或失败;
		 * @param handle_stream 连接建立成功则得到一个非0的连接句柄; 主动连接失败则为0
		 * @param ip 连接的远程地址
		 * @param port 连接的远程端口
		 */
		void onOpen(long handle_stream, String ip, int port);

		/**
		 * 连接关闭. 每个连接的onClose必定一一对应此连接的onOpen. 如果这里抛出异常会忽略,而不会触发onException
		 * @param handle_stream 连接句柄. 注意这个句柄此时已经无效,不能再用于send和close操作
		 * @param from 连接断开的来源
		 * @param errcode 连接断开的错误码
		 */
		void onClose(long handle_stream, int from, int errcode);

		/**
		 * 从某连接接收到数据
		 * @param handle_stream 连接句柄
		 * @param len 接收的数据长度. 接收的缓冲区从libuv_loop_buffer获取
		 */
		void onRecv(long handle_stream, int len);

		/**
		 * 上次数据发送完成(已成功放入待发送的TCP缓冲区)
		 * @param handle_stream 连接句柄
		 * @param buffer 上次调用send对应的缓冲区
		 */
		void onSend(long handle_stream, ByteBuffer buffer);

		/**
		 * onOpen/onRecv/onSend中抛出异常的统一处理. 一般在这里需要对连接句柄调用close操作
		 * @param handle_stream 出现异常的连接句柄. 无连接时为0
		 * @param ex 待处理的异常
		 */
		void onException(long handle_stream, Throwable ex);
	}

	/**
	 * 创建一个libuv的处理循环(uv_loop_init)
	 * @param handler 处理此循环的回调
	 * @return 处理循环的句柄
	 */
	public static native long libuv_loop_create(LibuvLoopHandler handler);

	/**
	 * 销毁一个libuv的处理循环(uv_loop_close)
	 * @param handle_loop 处理循环的句柄
	 * @return 0表示正常销毁. 如果该循环还可能继续接收事件处理,则返回非0表示不能销毁成功
	 */
	public static native int libuv_loop_destroy(long handle_loop);

	/**
	 * 获取绑定到loop循环的固定的读缓冲区. 一个loop循环只需获取一次
	 * @param handle_loop 处理循环的句柄
	 * @return 读缓冲区. 一定是DirectByteBuffer类型,在libuv_loop_destroy之前一直有效,但只在onRecv中访问是安全的,其中position一直保持0,有效长度见onRecv的len参数
	 */
	public static native ByteBuffer libuv_loop_buffer(long handle_loop);

	/**
	 * 持续处理一个libuv的循环(uv_run). 仅在无事件监听的情况下返回,handler只会在这里回调
	 * @param handle_loop 处理循环的句柄
	 * @param mode 0/1/2: UV_RUN_DEFAULT/UV_RUN_ONCE/UV_RUN_NOWAIT
	 * @return <0表示失败; 0/1表示不同的运行状态
	 */
	public static native int libuv_loop_run(long handle_loop, int mode);

	/**
	 * 开启本地端口的TCP被动连接监听(uv_tcp_init; uv_ip4_addr; uv_tcp_bind; uv_listen; ...; uv_accept; uv_read_start)
	 * <br>自动配置(uv_tcp_nodelay; uv_tcp_keepalive; uv_tcp_simultaneous_accepts)
	 * @param handle_loop 处理循环的句柄
	 * @param ip 本地监听的IP. null或"0.0.0.0"表示全部的本地IP
	 * @param port 本地监听的端口
	 * @param backlog 等待被动连接的队列最大数量
	 * @return 0表示绑定成功,后续会回调onOpen来通知成功的新连接(accept回调处理的一些异常情况目前被底层忽略而没有途径响应到handler中)
	 */
	public static native int libuv_tcp_bind(long handle_loop, String ip, int port, int backlog);

	/**
	 * 开启一个远程IP端口的TCP主动连接(uv_tcp_init; uv_ip4_addr; uv_tcp_connect; ...; uv_read_start)
	 * <br>自动配置(uv_tcp_nodelay; uv_tcp_keepalive; uv_tcp_simultaneous_accepts)
	 * @param handle_loop 处理循环的句柄
	 * @param ip 主动连接的远程IP
	 * @param port 主动连接的远程端口
	 * @return 0表示连接初步成功,最终结果等回调onOpen(成功时)或onClose(失败时)
	 */
	public static native int libuv_tcp_connect(long handle_loop, String ip, int port);

	/**
	 * 向连接发送数据(uv_write). 只能在循环处理所在的线程调用
	 * @param handle_stream 处理循环的句柄
	 * @param buffer 待发送的数据缓冲区. 必须是DirectByteBuffer类型,无视其中的position和limit,以后面2个参数为准
	 * @param pos 待发送的数据缓冲区起始位置
	 * @param len 待发送的数据长度
	 * @return 0表示发送初步成功,最终结果等回调onSend
	 */
	public static native int libuv_tcp_send(long handle_stream, ByteBuffer buffer, int pos, int len);

	/**
	 * 主动关闭连接(uv_close). 只能在循环处理所在的线程调用
	 * @param handle_stream 处理循环的句柄
	 * @param errcode 关闭连接附带的错误码,传到onClose的参数中
	 * @return 0表示关闭初步成功,后续会回调onClose
	 */
	public static native int libuv_tcp_close(long handle_stream, int errcode);
}
