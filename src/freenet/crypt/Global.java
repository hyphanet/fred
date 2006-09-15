/*
  Global.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.crypt;

import net.i2p.util.NativeBigInteger;

/**
 * This class contains global public keys used by Freenet.  These 
 * include the Diffie-Hellman key exchange modulus, DSA groups, and 
 * any other future values
 *
 * @author Scott 
 */
public final class Global {

	/**
	 * 2048-bit DSA group.
	 * SEED: 315a9f1fa8000fbc5aba458476c83564b5927099ddb5a3df58544e37b996fdfa9e644f5d35f7d7c8266fc485b351ace2b06a11e24a17cc205e2a58f0a4147ed4
	 * COUNTER: 1653
	 * Primality of q, 2q+1, and p is assured 2^200:1.
	 */
	public static final DSAGroup DSAgroupBigA = 
		new DSAGroup(
			new NativeBigInteger( /* p */
				"008608ac4f55361337f2a3e38ab1864ff3c98d66411d8d2afc9c526320c541f65078e86bc78494a5d73e4a9a67583f941f2993ed6c97dbc795dd88f0915c9cfbffc7e5373cde13e3c7ca9073b9106eb31bf82272ed0057f984a870a19f8a83bfa707d16440c382e62d3890473ea79e9d50c4ac6b1f1d30b10c32a02f685833c6278fc29eb3439c5333885614a115219b3808c92a37a0f365cd5e61b5861761dad9eff0ce23250f558848f8db932b87a3bd8d7a2f7cf99c75822bdc2fb7c1a1d78d0bcf81488ae0de5269ff853ab8b8f1f2bf3e6c0564573f612808f68dbfef49d5c9b4a705794cf7a424cd4eb1e0260552e67bfc1fa37b4a1f78b757ef185e86e9", 16),
			new NativeBigInteger( /* q */
				"00b143368abcd51f58d6440d5417399339a4d15bef096a2c5d8e6df44f52d6d379", 16),
			new NativeBigInteger( /* g */
				"51a45ab670c1c9fd10bd395a6805d33339f5675e4b0d35defc9fa03aa5c2bf4ce9cfcdc256781291bfff6d546e67d47ae4e160f804ca72ec3c5492709f5f80f69e6346dd8d3e3d8433b6eeef63bce7f98574185c6aff161c9b536d76f873137365a4246cf414bfe8049ee11e31373cd0a6558e2950ef095320ce86218f992551cc292224114f3b60146d22dd51f8125c9da0c028126ffa85efd4f4bfea2c104453329cc1268a97e9a835c14e4a9a43c6a1886580e35ad8f1de230e1af32208ef9337f1924702a4514e95dc16f30f0c11e714a112ee84a9d8d6c9bc9e74e336560bb5cd4e91eabf6dad26bf0ca04807f8c31a2fc18ea7d45baab7cc997b53c356", 16));
	
    public static final DSAGroup 
    /* -- DSA Group A (v2)-----------------------------------
     * For use in KSKs 
     * 
     * DSA modulus (|p|=1024 |q|=160 |g|=1022)
     *
     * Generation seeds:
     *
     * S=c4ef659a cb213aef 6e8cbff5 219af70a 
     *   64d9d329 d5fc9872 7678d258 8cc5a73b
     * C=154
     * N=205523667749658222872393179600727299639115513849
     * Primality confidence: 1/2^80
     */
	DSAgroupA = new DSAGroup(
	 new NativeBigInteger(                              /* p */
	   "a730059618b7c353000000000000000000000000000000000" +
	   "00000002e256dc149bbff8a5b1e3f128e77398b31ca944577" +
	   "04ecfa41b88deadc1e130ebbe6f345c3f17cb98d67315e0c0" +
	   "24c400b2af53711796a6a3bdb3c351dd97772635290a6bd30" +
	   "e7f46cbc52c82ca466aaa1d3dce93e65f87118ce885b2fd35" +
	   "643a1580597", 16),
	 new NativeBigInteger(                             /* q */
	   "e256bde3d85169cf6665c642e2678a1e36527423",16),
	 new NativeBigInteger(                             /* g */
           "81b86292391795a56d775d9447b1115c28c838427f6611098" +
	   "d3d4c12b63842e028294dbbf2bb88c6456da820e90a989c0d" +
	   "2c89e66f7f5283cc1041d8abfe0c71ae2754a07e8e413c844" +
	   "08e43ff863e9857824271db3d2fa4ddaf6334e913e82b0c0d" +
	   "09ea58486d44541c2f6a29cfd914844951031f52d0b224f8e" +
	   "7d0112c505", 16)),

    /* -- DSA Group B -----------------------------------
     * Used in SVKs if the Storable.Group field is unspecified
     * 
     * DSA modulus (|p|=1024 |q|=160 |g|=1023)
     *
     * Generation seeds:
     *
     * S=a6af7e8c fad3503e 20fe4dd7 3a9f574e
     *   dee8a3c7 ee819adb 837d0d8c 6a263d78
     * C=166
     * N=841824943102600080885322463644579019321817144754169
     * Primality confidence: 1/2^80
     */
	DSAgroupB = new DSAGroup(
	 new NativeBigInteger(                              /* p */
	   "c9381f278f7312c7fffffffffffffffffffffffffffffffff" +
	   "fffffffa8a6d5db1ab21047302cf6076102e67559e1569484" +
	   "6e3c7ceb4e18b6c652aedcfb337af057bdc12dcfc452d3ae4" +
	   "cfc5c3b7586804d4983bd5370db5512cf313e9a2c9c138c60" +
	   "2901135c4cfbcbe92d29fe744831f63e3273908c4f62f2129" +
	   "1840350f1e5",16),
	 new NativeBigInteger(                             /* q */
	   "c88fa2a0b1e70ba3876a35140fddce3c683706ad", 16),
	 new NativeBigInteger(                             /* g */
	   "65d3ccb70df16dc08822be40736bf951383f6c03ddfd51c1a" +
	   "41627fafb2b7f74a1e65ade0ab9f7c189c497cfb6fe6e9e7b" +
	   "a4160d7fd15bae68bff0e4a96f412e85924bcc89fee431406" +
	   "13afd124f425f891a2d3022f0a0444692e510fc5310360a21" +
	   "e3f729ab93f2ad81b0bbe27d86bc65cf385036969ede2473e" +
	   "6017df36d12",16)),

    /* -- DSA Group C -----------------------------------
     * Used as the default modulus for node public-key 
     * encryption and digital signatures.
     * 
     * DSA modulus (|p|=1024 |q|=160 |g|=1024)
     *
     * Generation seeds:
     *
     * S=567ae92a 134fdbbf 02377df9 a8ea6339
     *   c8484f12 bba6ad64 83e83c17 664df89d
     * C=203
     * N=115699539186647299839021270648563707381598615552361084141699065
     * Primality confidence: 1/2^80
     */
	DSAgroupC = new DSAGroup(
	 new NativeBigInteger(                              /* p */
           "cb0a782c7abff492000000000000000000000000000000000" +
	   "0000000023d662854a10e52de49da383d9ee21d7a337213d2" +
	   "4ed096f95a5d37b8537bbaa58a2a6b26bd328f6a32cec7718" +
	   "0f78d5be43d80e813e4018d09da38bd58fd615c01fbab492e" +
	   "c203c69e3da9fd682ce8aa98f15ad8057970edb44fe1ed08e" +
	   "0462e5b8d97",16),
	 new NativeBigInteger(                             /* q */
	   "ef1f7a7a73362e526515f348075aee265e9eff45", 16),
	 new NativeBigInteger(                             /* g */
	   "930168de21e7fb66c0375e08e964255a0f7f0ad54507a5186" + 
	   "4afdc686f36be8bb8b7865408116060c5f34f94b5146cbef9" +
	   "e4adb70324fba01d34c1c60817cbadf6854d654176cb391de" +
	   "0d41e0f0fbbc8ceea5546c09a676b0d9a9988c7a1ce36ce31" +
	   "596037a18b4d540374bdf2ad071a3f8dd1015a9d8ba0f0d51" +
	   "cde212db6da",16));

    public static final DHGroup 
    /* -- Diffie-Hellman Group A ----------------------------
     * For use in internode symmetric-cipher key exchange
     * 
     * Diffie-Hellman KE modulus (|p|=1024, g=2)
     * 
     * Taken from the IPsec standard
     */
	DHgroupA = new DHGroup(
         new NativeBigInteger(
           "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1"+
	   "29024E088A67CC74020BBEA63B139B22514A08798E3404DD"+
	   "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245"+
	   "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"+
	   "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE65381"+
	   "FFFFFFFFFFFFFFFF", 16),
	 Util.TWO);

    public static void main(String[] args) {
	System.err.println(DSAgroupA.toString() + '\t' + 
			   DSAgroupA.fingerprintToString());
	System.err.println(DSAgroupB.toString() + '\t' + 
			   DSAgroupB.fingerprintToString());
	System.err.println(DSAgroupC.toString() + '\t' + 
			   DSAgroupC.fingerprintToString());
	System.err.println(DHgroupA.toString() + '\t' + 
			   DHgroupA.fingerprintToString());
    }
}
