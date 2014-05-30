/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.crypt;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.HexUtil;
import freenet.support.Logger;

import net.i2p.util.NativeBigInteger;

//~--- JDK imports ------------------------------------------------------------

import java.math.BigInteger;

public class DiffieHellmanLightContext extends KeyAgreementSchemeContext {
    private static volatile boolean logMINOR;

    static {
        Logger.registerClass(DiffieHellmanLightContext.class);
    }

    /** My exponent. */
    public final NativeBigInteger myExponent;

    /** My exponential. This is group.g ^ myExponent mod group.p */
    public final NativeBigInteger myExponential;
    public final DHGroup group;

    public DiffieHellmanLightContext(DHGroup group, NativeBigInteger myExponent, NativeBigInteger myExponential) {
        this.myExponent = myExponent;
        this.myExponential = myExponential;
        this.group = group;
        this.lastUsedTime = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append(super.toString());
        sb.append(": myExponent=");
        sb.append(HexUtil.toHexString(myExponent));
        sb.append(", myExponential=");
        sb.append(HexUtil.toHexString(myExponential));

        return sb.toString();
    }

    /*
     * Calling the following is costy; avoid
     */
    public byte[] getHMACKey(NativeBigInteger peerExponential) {
        lastUsedTime = System.currentTimeMillis();

        BigInteger P = group.getP();
        NativeBigInteger sharedSecret = (NativeBigInteger) peerExponential.modPow(myExponent, P);

        if (logMINOR) {
            Logger.minor(this, "P: " + HexUtil.biToHex(P));
            Logger.minor(this, "My exponent: " + HexUtil.toHexString(myExponent));
            Logger.minor(this, "My exponential: " + HexUtil.toHexString(myExponential));
            Logger.minor(this, "Peer's exponential: " + HexUtil.toHexString(peerExponential));
            Logger.minor(this, "g^ir mod p = " + HexUtil.toHexString(sharedSecret));
        }

        return sharedSecret.toByteArray();
    }

    @Override
    public byte[] getPublicKeyNetworkFormat() {
        return stripBigIntegerToNetworkFormat(myExponential);
    }

    private byte[] stripBigIntegerToNetworkFormat(BigInteger exponential) {
        byte[] data = exponential.toByteArray();
        int targetLength = DiffieHellman.modulusLengthInBytes();

        assert(exponential.signum() == 1);

        if (data.length != targetLength) {
            byte[] newData = new byte[targetLength];

            if ((data.length == targetLength + 1) && (data[0] == 0)) {

                // Sign bit
                System.arraycopy(data, 1, newData, 0, targetLength);
            } else if (data.length < targetLength) {
                System.arraycopy(data, 0, newData, targetLength - data.length, data.length);
            } else {
                throw new IllegalStateException("Too long!");
            }

            data = newData;
        }

        return data;
    }
}
