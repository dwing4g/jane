using Jane.Bean;

namespace Jane.handler.testclient
{
	public class TestEmptyHandler
	{
		/*\
		\*/

		public static void onProcess(NetManager manager, IBean _arg_)
		{
			TestEmpty arg = (TestEmpty)_arg_;
			System.Console.WriteLine("{0}.onProcess: arg={1}", arg.GetType().Name, arg);
		}
	}
}
