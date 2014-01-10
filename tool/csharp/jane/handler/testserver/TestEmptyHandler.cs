using jane.bean;

namespace jane.handler.testserver
{
	public class TestEmptyHandler : BeanHandler<TestEmpty>
	{
		/*\
		\*/

		public override void onProcess(object manager, object session, TestEmpty arg)
		{
			// Log.log.debug("{}.onProcess: arg={}", getClass().getName(), arg);
		}
	}
}
