/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.security.spec.ECGenParameterSpec;

public enum KeyPairType {
	@Deprecated
	DSA(),
	ECP256("EC", "secp256r1"),
	ECP384("EC", "secp384r1"),
	ECP512("EC",  "secp521r1");
	
	public final String alg;
	public final String specName;
	public final ECGenParameterSpec spec;
	
	KeyPairType(){
		alg = name();
		specName = alg;
		spec = null;
	}
	
	KeyPairType(String alg, String specName){
		this.alg = alg;
		this.specName = specName;
		spec = new ECGenParameterSpec(specName);
	}
}
