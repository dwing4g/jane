package limax.edb;

import java.util.concurrent.locks.ReentrantLock;

final class HashLock {
	private final ReentrantLock[] locks;

	HashLock(int size) {
		locks = new ReentrantLock[size];
		for (int i = 0; i < size; i++)
			locks[i] = new ReentrantLock();
	}

	void lock(long n) {
		int key = (int) ((n >>> 32) ^ n) & Integer.MAX_VALUE;
		locks[key % locks.length].lock();
	}

	boolean tryLock(long n) {
		int key = (int) ((n >>> 32) ^ n) & Integer.MAX_VALUE;
		return locks[key % locks.length].tryLock();
	}

	void unlock(long n) {
		int key = (int) ((n >>> 32) ^ n) & Integer.MAX_VALUE;
		locks[key % locks.length].unlock();
	}
}
