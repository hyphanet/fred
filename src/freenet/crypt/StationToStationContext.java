/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Random;

import net.i2p.util.NativeBigInteger;
import freenet.support.HexUtil;
import freenet.support.Logger;

public class StationToStationContext extends KeyAgreementSchemeContext {

    // Set on startup
    
    /** Random number */
    final NativeBigInteger myRandom;
    
    /** My exponential */
    final NativeBigInteger myExponential;
    
    /** His pubkey */
    final DSAPublicKey hisPubKey;
    
    /** Our private key */
    final DSAPrivateKey myPrivateKey;
    
    /** The group we both share */
    final DSAGroup group;
    
    /** The rng */
    final RandomSource random;
    
    // Generated or set later
    NativeBigInteger hisExponential = null;
    NativeBigInteger key = null;
    
    boolean logMINOR;

    public StationToStationContext(DSAPrivateKey ourKey, DSAGroup group, DSAPublicKey hisKey, RandomSource rand) {
        this.myPrivateKey = ourKey;
        this.random = rand;
        this.group = group;
        this.hisPubKey = hisKey;
        // How big is the random ? FIXME!
        this.myRandom = new NativeBigInteger(2048, rand);
        // Not sure of what I'm doing below.
        this.myExponential = (NativeBigInteger) group.getG().modPow(myRandom, group.getQ());
        lastUsedTime = System.currentTimeMillis();
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
    }

    public synchronized NativeBigInteger getOurExponential() {
        lastUsedTime = System.currentTimeMillis();
        return myExponential;
    }
    
    public synchronized void setOtherSideExponential(NativeBigInteger a) {
        lastUsedTime = System.currentTimeMillis();
        if(hisExponential != null) {
        	if(!hisExponential.equals(a))
        		throw new IllegalStateException("Assigned other side exponential twice");
        	else return;
        }
        if(a == null) throw new NullPointerException();
        hisExponential = a;
    }

    public synchronized byte[] getKey() {
        lastUsedTime = System.currentTimeMillis();
        if(hisExponential == null) throw new IllegalStateException("Can't call getKey() until setOtherSideExponential() has been called!");
        if(key != null) return key.toByteArray();
        
        // Calculate key
        if(logMINOR)
            Logger.minor(this, "My exponent: "+myExponential.toHexString()+", my random: "+myRandom+", peer's exponential: "+hisExponential.toHexString());
        // Not sure of what I'm doing below
        key = (NativeBigInteger) hisExponential.modPow(myRandom, group.getQ());

        if(logMINOR)
            Logger.minor(this, "Key="+HexUtil.bytesToHex(key.toByteArray()));
        return key.toByteArray();
    }
    
    public synchronized byte[] concatAndSignAndCrypt(){
    	lastUsedTime = System.currentTimeMillis();
    	if(hisExponential == null) throw new IllegalStateException("Can't call concatAndSignAndCrypt() until setOtherSideExponential() has been called!");
    	if(key == null)  getKey();
    	
    	MessageDigest md = SHA256.getMessageDigest();
    	
    	String message = "(" + myExponential + ',' + hisExponential + ')';
    	DSASignature signature = DSA.sign(group, myPrivateKey, new BigInteger(md.digest(message.getBytes())), random);
    	
    	if(logMINOR)
            Logger.minor(this, "The concat result : "+message+". Its signature : "+signature);
    	
    	ByteArrayOutputStream os = new ByteArrayOutputStream();
    	CipherOutputStream cos = new CipherOutputStream(getCipher(), os);
    	byte[] result = null;
    	try{
    		cos.write(signature.toString().getBytes());
        	cos.flush();
        	cos.close();
        	result = os.toByteArray();
        	os.close();
    	} catch(IOException e){
    		Logger.error(this, "Error :"+e);
    		e.printStackTrace();
    	}

    	return result;
    }
    
    public synchronized boolean isAuthentificationSuccessfull(byte[] data){
    	lastUsedTime = System.currentTimeMillis();
    	if(data == null) return false;
    	if(hisExponential == null) throw new IllegalStateException("Can't call concatAndSignAndCrypt() until setOtherSideExponential() has been called!");
    	if(key == null)  getKey();
    	
    	ByteArrayInputStream is = new ByteArrayInputStream(data);
    	EncipherInputStream ei = new EncipherInputStream(is, getCipher());
    	final String message = "(" + hisExponential + ',' + myExponential + ')';

    	MessageDigest md = SHA256.getMessageDigest();
        try{
    		String signatureToCheck = ei.toString();
    		ei.close();
    		is.close();

    		if(signatureToCheck != null)
    			if(DSA.verify(hisPubKey, new DSASignature(signatureToCheck), new BigInteger(md.digest(message.getBytes()))))
    				return true;

    	} catch(IOException e){
    		Logger.error(this, "Error :"+e);
    		e.printStackTrace();
    	}

    	return false;
    }

    /**
     * @return True if getCipher() will work. If this returns false, getCipher() will
     * probably NPE.
     */
    public boolean canGetCipher() {
        return hisExponential != null;
    }
}
