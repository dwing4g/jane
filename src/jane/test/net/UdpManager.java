package jane.test.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import jane.core.Log;

/**
 * UDP网络管理器
 */
public abstract class UdpManager
{
	private static final int SOCKET_RECV_BUFFER_SIZE = 16 * 1024 * 1024; // socket接收缓冲区大小(字节)
	private static final int SOCKET_SEND_BUFFER_SIZE = 16 * 1024 * 1024; // socket发送缓冲区大小(字节)
	private static final int PACKET_POOL_MAX_SIZE	 = 50000;			 // 数据表对象池的上限数量
	private static final int PACKET_BUFFER_CAPACITY	 = 576 - 20 - 8;	 // 最大的数据包内容大小(548字节)

	private static final DatagramPacket[] freeList = new DatagramPacket[PACKET_POOL_MAX_SIZE]; // 全局的数据包池. 只存放空闲的对象,初始为空
	private static int					  freeSize;											   // 数据包池的当前空闲对象数量

	protected final String	 name = getClass().getSimpleName();	// 实际的类名
	protected DatagramSocket socket;							// 关联的UDP socket
	private Thread			 receiveThread;						// 接收UDP包的线程

	/**
	 * 分配DatagramPacket对象. 容量固定为PACKET_BUFFER_CAPACITY, 没设置地址
	 */
	public static DatagramPacket allocPacket()
	{
		synchronized(freeList)
		{
			int size = freeSize;
			if(size > 0)
			{
				freeSize = --size;
				DatagramPacket packet = freeList[size];
				freeList[size] = null;
				packet.setLength(PACKET_BUFFER_CAPACITY);
				return packet;
			}
		}
		return new DatagramPacket(new byte[PACKET_BUFFER_CAPACITY], PACKET_BUFFER_CAPACITY);
	}

	/**
	 * 回收DatagramPacket对象
	 */
	public static void freePacket(DatagramPacket packet)
	{
		synchronized(freeList)
		{
			final int size = freeSize;
			if(size < PACKET_BUFFER_CAPACITY)
			{
				freeList[size] = packet;
				freeSize = size + 1;
			}
		}
	}

	public boolean isRunning()
	{
		return socket != null;
	}

	/**
	 * 启动服务. 可以指定地址和端口
	 */
	public synchronized void start(SocketAddress addr) throws IOException
	{
		if(isRunning())
			throw new IllegalStateException(name + " has already started");

		final DatagramSocket s = new DatagramSocket(addr != null ? addr : new InetSocketAddress(0));
		s.setReceiveBufferSize(SOCKET_RECV_BUFFER_SIZE);
		s.setSendBufferSize(SOCKET_SEND_BUFFER_SIZE);
		socket = s;

		receiveThread = new Thread(name + "-ReceiveThread")
		{
			@Override
			public void run()
			{
				final String threadName = Thread.currentThread().getName();
				Log.info("{} started", threadName);
				try
				{
					for(;;)
					{
						try
						{
							if(s.isClosed())
								break;
							final DatagramPacket packet = allocPacket();
							s.receive(packet); // 当前socket缓冲区为空时会阻塞. 返回时会为packet设置地址. 出错时会抛各种异常
							onReceive(packet); // 业务处理可能会抛各种异常
						}
						catch(InterruptedException e)
						{
							throw e;
						}
						catch(Throwable e)
						{
							Log.error(e, "{} exception:", threadName);
							Thread.sleep(1); // 避免频繁出错
						}
					}
				}
				catch(InterruptedException interruptedException)
				{
					Log.info("{} interrupted", threadName);
				}
				finally
				{
					Log.info("{} stopped", threadName);
				}
			}
		};
		receiveThread.start();

		Log.info("{}({}): started", name, s.getLocalSocketAddress());
	}

	/**
	 * 停止服务. 不清理消息队列
	 */
	public synchronized void stop() throws InterruptedException
	{
		if(socket != null)
		{
			final SocketAddress addr = socket.getLocalSocketAddress();
			socket.close();
			socket = null;
			Log.info("{}({}): stopped", name, addr);
		}
		if(receiveThread != null)
		{
			receiveThread.interrupt();
			receiveThread.join();
			receiveThread = null;
		}
	}

	/**
	 * 接收DatagramPacket数据包后的处理方法. 不再引用packet时应调用freePacket方法回收
	 */
	protected abstract void onReceive(DatagramPacket packet) throws InterruptedException;

	/**
	 * 发送DatagramPacket数据包. 通常不会阻塞,除非发送缓冲区满. 此方法不回收packet对象
	 */
	public boolean send(DatagramPacket packet) throws IOException
	{
		final DatagramSocket s = socket;
		if(s == null)
			return false;
		s.send(packet);
		return true;
	}
}
