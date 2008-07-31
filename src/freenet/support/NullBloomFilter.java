/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

/**
 * @author sdiz
 */
public class NullBloomFilter extends BloomFilter {
	protected NullBloomFilter(int length, int k) {
		super(length, k);
	}

	@Override
	public boolean checkFilter(byte[] key) {
		return true;
	}

	@Override
	public void addKey(byte[] key) {
		// ignore
	}

	@Override
	public void removeKey(byte[] key) {
		// ignore
	}

	@Override
	protected boolean getBit(int offset) {
		// ignore
		return true;
	}

	@Override
	protected void setBit(int offset) {
		// ignore
	}

	@Override
	protected void unsetBit(int offset) {
		// ignore
	}

	@Override
	public void fork(int k) {
		return;
	}

	@Override
	public void discard() {
		return;
	}

	@Override
	public void merge() {
		return;
	}
}
