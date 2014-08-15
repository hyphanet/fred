/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.security.spec.ECGenParameterSpec;

/**
 * Keeps track of curve names and specs for EC based algorithms available to Freenet. 
 * Also includes DSA for legacy support of old network content. 
 * @author unixninja92
 *
 */
public enum KeyPairType {
    /**
     * @deprecated DSA should only be used for legacy support of old network
     * content. Replaced by {@link #ECP256}
     */
    @Deprecated
    DSA(),
    ECP256("EC", "secp256r1", 91),
    ECP384("EC", "secp384r1", 120),
    ECP521("EC",  "secp521r1", 158);

    public final String alg;
    public final String specName;
    /** Expected size of a DER encoded pubkey in bytes */
    public final int modulusSize;
    public final ECGenParameterSpec spec;

    /**
     * Creates the DSA enum value. 
     */
    private KeyPairType(){
        alg = name();
        specName = alg;
        modulusSize = 128;
        spec = null;
    }

    /**
     * Creates EC enum values and creates ECGenparameterSpecs for them. 
     * @param alg What algorithm KeyPairGenerators should use
     * @param specName The elliptic curve to use. 
     * @param modulusSize Expected size of a DER encoded pubkey in bytes
     */
    private KeyPairType(String alg, String specName, int modulusSize){
        this.alg = alg;
        this.specName = specName;
        this.modulusSize = modulusSize;
        spec = new ECGenParameterSpec(specName);
    }
}
