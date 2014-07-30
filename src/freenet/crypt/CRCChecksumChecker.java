package freenet.crypt;

import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import freenet.support.Fields;

public class CRCChecksumChecker extends ChecksumChecker {

    @Override
    public int checksumLength() {
        return 4;
    }

    @Override
    public OutputStream checksumWriter(OutputStream os) {
        return new ChecksumOutputStream(os, new CRC32(), true);
    }

    @Override
    public boolean checkChecksum(byte[] data, int offset, int length, byte[] checksum) {
        if(checksum.length != 4) throw new IllegalArgumentException();
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        int computed = (int)crc.getValue();
        int stored = Fields.bytesToInt(checksum);
        return computed == stored;
    }

    @Override
    public byte[] appendChecksum(byte[] data) {
        byte[] output = new byte[data.length+4];
        System.arraycopy(data, 0, output, 0, data.length);
        Checksum crc = new CRC32();
        crc.update(data, 0, data.length);
        byte[] checksum = Fields.intToBytes((int)crc.getValue());
        System.arraycopy(checksum, 0, output, data.length, 4);
        return output;
    }

    @Override
    public byte[] generateChecksum(byte[] data) {
        Checksum crc = new CRC32();
        crc.update(data, 0, data.length);
        return Fields.intToBytes((int)crc.getValue());
    }

}
