using jane.bean;

namespace jane.handler.testserver
{
	public class TestEmptyHandler : BeanHandler<TestEmpty>
	{
		/*\
		\*/

		public override void onProcess(NetManager mgr, TestEmpty arg)
		{
			// System.Console.WriteLine("{0}.onProcess: arg={1}", GetType().Name, arg);
		}
	}
}
