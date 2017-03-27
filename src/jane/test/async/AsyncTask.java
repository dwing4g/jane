package jane.test.async;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public abstract class AsyncTask implements Runnable
{
	protected static void yield(Runnable r)
	{
		AsyncManager.get().submit(r);
	}

	protected static void await(AsyncTimerTask task)
	{
		AsyncManager.get().submit(task);
	}

	protected static void await(int delayMs, Runnable r)
	{
		AsyncManager.get().submit(delayMs, r);
	}

	protected void awaitReadFile(String path, final AsyncHandler<byte[]> handler)
	{
		int len = (int)new File(path).length();
		if(len > 0)
		{
			try
			{
				@SuppressWarnings("resource")
				AsynchronousFileChannel afc = AsynchronousFileChannel.open(Paths.get(path), StandardOpenOption.READ);
				final ByteBuffer bb = ByteBuffer.wrap(new byte[len]);
				afc.read(bb, len, afc, new CompletionHandler<Integer, AsynchronousFileChannel>()
				{
					@Override
					public void completed(Integer result, final AsynchronousFileChannel afc2)
					{
						AsyncManager.get().submit(new Runnable()
						{
							@Override
							public void run()
							{
								try
								{
									afc2.close();
								}
								catch(IOException e)
								{
									AsyncManager.onException(AsyncTask.this, e);
								}
								handler.onHandler(bb.array());
							}
						});
					}

					@Override
					public void failed(Throwable exc, AsynchronousFileChannel afc2)
					{
						try
						{
							afc2.close();
						}
						catch(IOException e)
						{
							AsyncManager.onException(AsyncTask.this, e);
						}
						handler.onHandler(null);
					}
				});
			}
			catch(IOException e)
			{
				AsyncManager.onException(AsyncTask.this, e);
				handler.onHandler(null);
			}
		}
		else
			handler.onHandler(null);
	}

	protected void awaitWriteFile(String path, byte[] data, final AsyncHandler<Boolean> handler)
	{
		try
		{
			@SuppressWarnings("resource")
			AsynchronousFileChannel afc = AsynchronousFileChannel.open(Paths.get(path), StandardOpenOption.WRITE, StandardOpenOption.CREATE);
			afc.write(ByteBuffer.wrap(data), 0, afc, new CompletionHandler<Integer, AsynchronousFileChannel>()
			{
				@Override
				public void completed(Integer result, final AsynchronousFileChannel afc2)
				{
					AsyncManager.get().submit(new Runnable()
					{
						@Override
						public void run()
						{
							try
							{
								afc2.close();
							}
							catch(IOException e)
							{
								AsyncManager.onException(AsyncTask.this, e);
							}
							handler.onHandler(true);
						}
					});
				}

				@Override
				public void failed(Throwable exc, AsynchronousFileChannel afc2)
				{
					try
					{
						afc2.close();
					}
					catch(IOException e)
					{
						AsyncManager.onException(AsyncTask.this, e);
					}
					handler.onHandler(null);
				}
			});
		}
		catch(IOException e)
		{
			AsyncManager.onException(AsyncTask.this, e);
			handler.onHandler(false);
		}
	}
}
