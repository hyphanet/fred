package freenet.crypt;

import java.security.interfaces.ECPublicKey;

import freenet.support.HexUtil;
import freenet.support.Logger;

public class ECDHLightContext extends KeyAgreementSchemeContext {
    static { Logger.registerClass(ECDHLightContext.class); }
    private static volatile boolean logMINOR;
    private static volatile boolean logDEBUG;
    
    public final ECDH ecdh;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(super.toString());
        return sb.toString();
    }

    public ECDHLightContext(ECDH.Curves curve) {
        this.ecdh = new ECDH(curve);
        this.lastUsedTime = System.currentTimeMillis();
    }
    
    public ECPublicKey getPublicKey() {
        return ecdh.getPublicKey();
    }

    /*
     * Calling the following is costy; avoid
     */
    public byte[] getHMACKey(ECPublicKey peerExponential) {
        synchronized(this) {
            lastUsedTime = System.currentTimeMillis();
        }
        byte[] sharedKey = ecdh.getAgreedSecret(peerExponential);

        if (logMINOR) {
            Logger.minor(this, "Curve in use: " + ecdh.curve.toString());
            if(logDEBUG) {
            	Logger.debug(this,
            			"My exponential: " + HexUtil.bytesToHex(ecdh.getPublicKey().getEncoded()));
            	Logger.debug(
            			this,
            			"Peer's exponential: "
            			+ HexUtil.bytesToHex(peerExponential.getEncoded()));
            	Logger.debug(this,
            			"SharedSecret = " + HexUtil.bytesToHex(sharedKey));
            }
        }

        return sharedKey;
    }

    @Override
    public byte[] getPublicKeyNetworkFormat() {
        return ecdh.getPublicKeyNetworkFormat();
    }
}
