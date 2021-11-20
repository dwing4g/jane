package jane.unittest;

import junit.framework.TestCase;

public class TestProcedure extends TestCase
{
	@Override
	protected void setUp()
	{
		System.out.println("in setUp");
	}

	@Override
	protected void tearDown()
	{
		System.out.println("in tearDown");
	}

	public void test()
	{
		System.out.println("in test");
	}
}
