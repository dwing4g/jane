using jane.bean;

namespace jane.handler.testclient
{
	public class TestEmptyHandler : BeanHandler<TestEmpty>
	{
		/*\
		\*/

		public override void onProcess(NetManager manager, TestEmpty arg)
		{
			System.Console.WriteLine("{0}.onProcess: arg={1}", GetType().Name, arg);
		}
	}
}
