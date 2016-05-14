/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import org.bouncycastle.crypto.params.DSAParameters;

import java.math.BigInteger;

/**
 * This class contains global public keys used by Freenet.  These 
 * include the Diffie-Hellman key exchange modulus, DSA groups, and 
 * any other future values
 *
 * @author Scott 
 */
public final class Global {
	public static final int GROUP_INDEX_BIG_A = 1;
	/**
	 * 2048-bit DSA group.
	 * SEED: 315a9f1fa8000fbc5aba458476c83564b5927099ddb5a3df58544e37b996fdfa9e644f5d35f7d7c8266fc485b351ace2b06a11e24a17cc205e2a58f0a4147ed4
	 * COUNTER: 1653
	 * Primality of q, 2q+1, and p is assured 2^200:1.
	 */
	public static final DSAGroup DSAgroupBigA =
		new DSAGroup(
		new BigInteger( /* p */
		"008608ac4f55361337f2a3e38ab1864ff3c98d66411d8d2afc9c526320c541f65078e86bc78494a5d73e4a9a67583f941f2993ed6c97dbc795dd88f0915c9cfbffc7e5373cde13e3c7ca9073b9106eb31bf82272ed0057f984a870a19f8a83bfa707d16440c382e62d3890473ea79e9d50c4ac6b1f1d30b10c32a02f685833c6278fc29eb3439c5333885614a115219b3808c92a37a0f365cd5e61b5861761dad9eff0ce23250f558848f8db932b87a3bd8d7a2f7cf99c75822bdc2fb7c1a1d78d0bcf81488ae0de5269ff853ab8b8f1f2bf3e6c0564573f612808f68dbfef49d5c9b4a705794cf7a424cd4eb1e0260552e67bfc1fa37b4a1f78b757ef185e86e9", 16),
		new BigInteger( /* q */
		"00b143368abcd51f58d6440d5417399339a4d15bef096a2c5d8e6df44f52d6d379", 16),
		new BigInteger( /* g */
		"51a45ab670c1c9fd10bd395a6805d33339f5675e4b0d35defc9fa03aa5c2bf4ce9cfcdc256781291bfff6d546e67d47ae4e160f804ca72ec3c5492709f5f80f69e6346dd8d3e3d8433b6eeef63bce7f98574185c6aff161c9b536d76f873137365a4246cf414bfe8049ee11e31373cd0a6558e2950ef095320ce86218f992551cc292224114f3b60146d22dd51f8125c9da0c028126ffa85efd4f4bfea2c104453329cc1268a97e9a835c14e4a9a43c6a1886580e35ad8f1de230e1af32208ef9337f1924702a4514e95dc16f30f0c11e714a112ee84a9d8d6c9bc9e74e336560bb5cd4e91eabf6dad26bf0ca04807f8c31a2fc18ea7d45baab7cc997b53c356", 16));

	/* NB: for some reason I can't explain, we're signing truncated hashes...
	   So we need to keep that code around

	   Ever since 567bf11a954edc31f3b6d4348782792fe7d5bae5 we are truncating all hashes
	    (in Freenet's case, we are always using a single group: q is constant - see above)
	   @see InsertableClientSSK

	   Ever since 2ffce6060b346c3a671887b51849f88482b882a9 we are verifying using both
	   the truncated and non-truncated version (wasting CPU cycles in the process)
	   @see SSKBlock

	   I guess it's not too bad since in most cases the signature will match on the first try:
	   Anything inserted post 2007 is signed using a truncated hash.
	*/
	static final BigInteger SIGNATURE_MASK = Util.TWO.pow(255).subtract(BigInteger.ONE);

	public static byte[] truncateHash(byte[] message) {
		BigInteger m = new BigInteger(1, message);
		m = m.and(SIGNATURE_MASK);
		return m.toByteArray();
	}
	public static final DSAParameters getDSAgroupBigAParameters() {
		return new DSAParameters(DSAgroupBigA.getP(),DSAgroupBigA.getQ(), DSAgroupBigA.getG());
	}
}
