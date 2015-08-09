package freenet.client.async;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;

import freenet.client.InsertContext;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.support.Logger;
import freenet.support.io.StorageFormatException;

public class CompatibilityAnalyser implements Serializable {

    private static final long serialVersionUID = 1L;
    CompatibilityMode min;
    CompatibilityMode max;
    byte[] cryptoKey;
    boolean dontCompress;
    boolean definitive;
    
    public CompatibilityAnalyser() {
        this.min = CompatibilityMode.COMPAT_UNKNOWN;
        this.max = CompatibilityMode.COMPAT_UNKNOWN;
        this.dontCompress = true;
    }
    
    public void merge(CompatibilityMode min, CompatibilityMode max, byte[] cryptoKey, boolean dontCompress, boolean definitive) {
        if(this.definitive) {
            Logger.warning(this, "merge() after definitive", new Exception("debug"));
            return;
        }
        assert(min != CompatibilityMode.COMPAT_CURRENT);
        assert(max != CompatibilityMode.COMPAT_CURRENT);
        if(definitive) this.definitive = true;
        if(!dontCompress) this.dontCompress = false;
        if(min.ordinal() > this.min.ordinal()) this.min = min;
        if(max.ordinal() < this.max.ordinal() || this.max == CompatibilityMode.COMPAT_UNKNOWN) this.max = max;
        if(this.cryptoKey == null) {
            this.cryptoKey = cryptoKey;
        } else if(cryptoKey != null && !Arrays.equals(this.cryptoKey, cryptoKey)) {
            Logger.error(this, "Two different crypto keys!");
            this.cryptoKey = null;
        }
    }

    public CompatibilityMode min() {
        return min;
    }
    
    public CompatibilityMode max() {
        return max;
    }

    public byte[] getCryptoKey() {
        return cryptoKey;
    }

    public boolean dontCompress() {
        return dontCompress;
    }

    public boolean definitive() {
        return definitive;
    }

    public InsertContext.CompatibilityMode[] getModes() {
        return new InsertContext.CompatibilityMode[] { min(), max() };
    }
    
    static final int VERSION = 2;

    public void writeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(VERSION);
        dos.writeShort(min.code);
        dos.writeShort(max.code);
        if(cryptoKey == null) {
            dos.writeBoolean(false);
        } else {
            dos.writeBoolean(true);
            assert(cryptoKey.length == 32);
            dos.write(cryptoKey);
        }
        dos.writeBoolean(dontCompress);
        dos.writeBoolean(definitive);
    }
    
    public CompatibilityAnalyser(DataInputStream dis) throws IOException, StorageFormatException {
        int ver = dis.readInt();
        if(ver != VERSION) throw new StorageFormatException("Unknown version for CompatibilityAnalyser");
        try {
        min = CompatibilityMode.byCode(dis.readShort());
        max = CompatibilityMode.byCode(dis.readShort());
        } catch (IllegalArgumentException e) {
            throw new StorageFormatException("Bad min value");
        }
        if(dis.readBoolean()) {
            cryptoKey = new byte[32];
            dis.readFully(cryptoKey);
        }
        dontCompress = dis.readBoolean();
        definitive = dis.readBoolean();
    }


}
