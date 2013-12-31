package jane.handler.testclient;

import org.apache.mina.core.session.IoSession;
import jane.bean.TestBean;
import jane.core.BeanHandler;
import jane.core.BeanManager;
import jane.core.Log;
import jane.core.RC4Filter;

public class TestBeanHandler extends BeanHandler<TestBean>
{
	/*\
	|*| int TEST_CONST1 = 5; // 测试类静态常量
	|*| String TEST_CONST2 = "test_const2";
	|*| int value1; // 字段的注释
	|*| long value2;
	\*/

	@Override
	public void onProcess(BeanManager manager, IoSession session, TestBean arg)
	{
		Log.log.debug("{}: arg={}", getClass().getName(), arg);

		RC4Filter filter = new RC4Filter();
		filter.setInputKey(new byte[] { 1, 2, 3 }, 3);
		filter.setOutputKey(new byte[] { 1, 2, 3 }, 3);
		session.getFilterChain().addFirst("enc", filter);

		manager.send(session, arg);
	}
}
