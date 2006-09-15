/*
  DiffieHellmanContext.java / Freenet
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

import java.security.MessageDigest;

import net.i2p.util.NativeBigInteger;
import freenet.support.HexUtil;
import freenet.support.Logger;

public class DiffieHellmanContext extends KeyAgreementSchemeContext {

    // Set on startup
    /** My exponent. We keep this and then raise our peer's exponential to this power. */
    final NativeBigInteger myExponent;
    /** My exponential. This is group.g ^ myExponent mod group.p */
    final NativeBigInteger myExponential;
    /** The group we both share */
    final DHGroup group;
    
    // Generated or set later
    NativeBigInteger peerExponential;

	public String toString() {
	    StringBuffer sb = new StringBuffer();
	    sb.append(super.toString());
	    sb.append(": myExponent=");
	    sb.append(myExponent.toHexString());
	    sb.append(", myExponential=");
	    sb.append(myExponential.toHexString());
	    if(peerExponential != null) {
	        sb.append(", peerExponential=");
	        sb.append(peerExponential.toHexString());
	    }
	    return sb.toString();
	}
    
    public DiffieHellmanContext(NativeBigInteger myExponent, NativeBigInteger myExponential, DHGroup group) {
        this.myExponent = myExponent;
        this.myExponential = myExponential;
        this.group = group;
        lastUsedTime = System.currentTimeMillis();
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
    }

    public synchronized NativeBigInteger getOurExponential() {
        lastUsedTime = System.currentTimeMillis();
        return myExponential;
    }

    public synchronized byte[] getKey() {
        lastUsedTime = System.currentTimeMillis();
        if(key != null) return key;
        
        // Calculate key
        if(logMINOR)
            Logger.minor(this, "My exponent: "+myExponent.toHexString()+", my exponential: "+myExponential.toHexString()+", peer's exponential: "+peerExponential.toHexString());
        NativeBigInteger sharedSecret =
            (NativeBigInteger) peerExponential.modPow(myExponent, group.getP());
        MessageDigest md;
        
        md = SHA256.getMessageDigest();
        key = md.digest(sharedSecret.toByteArray());
        if(logMINOR)
            Logger.minor(this, "Key="+HexUtil.bytesToHex(key));
        return key;
    }
    
    public synchronized void setOtherSideExponential(NativeBigInteger a) {
        lastUsedTime = System.currentTimeMillis();
        if(peerExponential != null) {
        	if(!peerExponential.equals(a))
        		throw new IllegalStateException("Assigned other side exponential twice");
        	else return;
        }
        if(a == null) throw new NullPointerException();
        peerExponential = a;
    }

    /**
     * @return True if getCipher() will work. If this returns false, getCipher() will
     * probably NPE.
     */
    public boolean canGetCipher() {
        return peerExponential != null;
    }
}
