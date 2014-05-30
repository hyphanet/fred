/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.crypt;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.HexUtil;
import freenet.support.Logger;

//~--- JDK imports ------------------------------------------------------------

import java.security.interfaces.ECPublicKey;

import javax.crypto.SecretKey;

public class ECDHLightContext extends KeyAgreementSchemeContext {
    private static volatile boolean logMINOR;
    private static volatile boolean logDEBUG;

    static {
        Logger.registerClass(ECDHLightContext.class);
    }

    public final ECDH ecdh;

    public ECDHLightContext(ECDH.Curves curve) {
        this.ecdh = new ECDH(curve);
        this.lastUsedTime = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append(super.toString());

        return sb.toString();
    }

    public ECPublicKey getPublicKey() {
        return ecdh.getPublicKey();
    }

    /*
     * Calling the following is costy; avoid
     */
    public byte[] getHMACKey(ECPublicKey peerExponential) {
        synchronized (this) {
            lastUsedTime = System.currentTimeMillis();
        }

        byte[] sharedKey = ecdh.getAgreedSecret(peerExponential);

        if (logMINOR) {
            Logger.minor(this, "Curve in use: " + ecdh.curve.toString());

            if (logDEBUG) {
                Logger.debug(this, "My exponential: " + HexUtil.bytesToHex(ecdh.getPublicKey().getEncoded()));
                Logger.debug(this, "Peer's exponential: " + HexUtil.bytesToHex(peerExponential.getEncoded()));
                Logger.debug(this, "SharedSecret = " + HexUtil.bytesToHex(sharedKey));
            }
        }

        return sharedKey;
    }

    @Override
    public byte[] getPublicKeyNetworkFormat() {
        return ecdh.getPublicKeyNetworkFormat();
    }
}
