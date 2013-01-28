package freenet.store;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import junit.framework.TestCase;

import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPrivateKey;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.Global;
import freenet.support.ByteArrayWrapper;
import freenet.support.math.MersenneTwister;

public class SimplePubkeyCacheTest extends TestCase {
	
	public void testSimple() {
		final int keys = 10;
		PubkeyStore pk = new PubkeyStore();
		new RAMFreenetStore<DSAPublicKey>(pk, keys);
		GetPubkey pubkeys = new SimpleGetPubkey(pk);
		DSAGroup group = Global.DSAgroupBigA;
		Random random = new MersenneTwister(1010101);
		HashMap<ByteArrayWrapper, DSAPublicKey> map = new HashMap<ByteArrayWrapper, DSAPublicKey>();
		for(int i=0;i<keys;i++) {
			DSAPrivateKey privKey = new DSAPrivateKey(group, random);
			DSAPublicKey key = new DSAPublicKey(group, privKey);
			byte[] hash = key.asBytesHash();
			ByteArrayWrapper w = new ByteArrayWrapper(hash);
			map.put(w, key.cloneKey());
			pubkeys.cacheKey(hash, key, false, false, false, false, false);
			assertTrue(pubkeys.getKey(hash, false, false, null).equals(key));
		}
		int x = 0;
		for(Map.Entry<ByteArrayWrapper, DSAPublicKey> entry : map.entrySet()) {
			x++;
			assertTrue(pubkeys.getKey(entry.getKey().get(), false, false, null).equals(entry.getValue()));
		}
		assert(x == keys);
	}

}
