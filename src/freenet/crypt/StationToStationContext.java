package freenet.crypt;

import java.math.BigInteger;
import java.util.Random;

import net.i2p.util.NativeBigInteger;
import freenet.support.HexUtil;
import freenet.support.Logger;

public class StationToStationContext extends KeyAgreementSchemeContext {

    // Set on startup
    
    /** Random number */
    final int myRandom;
    
    /** My exponential */
    final NativeBigInteger myExponential;
    
    /** His pubkey */
    final DSAPublicKey hisPubKey;
    
    /** Our private key */
    final DSAPrivateKey ourPrivateKey;
    
    /** The group we both share */
    final DSAGroup group;
    
    /** The rng */
    final Random random;
    
    // Generated or set later
    NativeBigInteger peerExponential = null;
    NativeBigInteger key = null;
    
    boolean logMINOR;

    public StationToStationContext(DSAPrivateKey ourKey, DSAGroup group, DSAPublicKey hisKey, Random rand) {
        this.ourPrivateKey = ourKey;
        this.random = rand;
        this.group = group;
        this.hisPubKey = hisKey;
        // How big is the random ?
        this.myRandom = random.nextInt();
        this.myExponential = (NativeBigInteger) group.getG().pow(myRandom);
        lastUsedTime = System.currentTimeMillis();
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
    }

    public synchronized NativeBigInteger getOurExponential() {
        lastUsedTime = System.currentTimeMillis();
        return myExponential;
    }

    public synchronized BigInteger getKey() {
        lastUsedTime = System.currentTimeMillis();
        if(key != null) return key;
        if(peerExponential == null) throw new IllegalStateException("Can't call getKey() until setOtherSideExponential() has been called!");
        
        // Calculate key
        if(logMINOR)
            Logger.minor(this, "My exponent: "+myExponential.toHexString()+", my random: "+myRandom+", peer's exponential: "+peerExponential.toHexString());
        key = (NativeBigInteger) peerExponential.pow(myRandom);

        if(logMINOR)
            Logger.minor(this, "Key="+HexUtil.bytesToHex(key.toByteArray()));
        return key;
    }
    
    public synchronized byte[] concatAndSignAndCrypt(){
    	if(peerExponential == null) throw new IllegalStateException("Can't call concatAndSign() until setOtherSideExponential() has been called!");
    	if(key == null)  throw new IllegalStateException("Can't call concatAndSign() until getKey() has been called!");
    	
    	String sig = new String(myExponential+","+peerExponential);
    	//FIXME: REDFLAG: it should be encrypted as well!
    	return DSA.sign(group, ourPrivateKey, new BigInteger(sig.getBytes()), random).toString().getBytes();
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
}
