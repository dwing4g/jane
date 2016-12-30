package limax.edb;

public interface QueryKey extends Query {
	boolean update(byte[] key);
}
