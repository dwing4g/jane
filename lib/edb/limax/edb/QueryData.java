package limax.edb;

public interface QueryData extends Query {
	boolean update(byte[] key, byte[] value);
}
