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
