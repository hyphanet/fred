package freenet.crypt;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import freenet.support.Fields;
import freenet.support.io.FileUtil;

public class CRCChecksumChecker extends ChecksumChecker {

    @Override
    public int checksumLength() {
        return 4;
    }

    @Override
    public OutputStream checksumWriter(OutputStream os, int prefix) {
        return new ChecksumOutputStream(os, new CRC32(), true, prefix);
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
    public byte[] generateChecksum(byte[] data, int offset, int length) {
        Checksum crc = new CRC32();
        crc.update(data, offset, length);
        return Fields.intToBytes((int)crc.getValue());
    }

    @Override
    public int getChecksumTypeID() {
        return ChecksumChecker.CHECKSUM_CRC;
    }

    @Override
    public void copyAndStripChecksum(InputStream is, OutputStream destination, long length) throws IOException, ChecksumFailedException {
        // FIXME refactor via a base class for ChecksumOutputStream.
        CRC32 crc = new CRC32();
        long remaining = length;
        byte[] buffer = new byte[FileUtil.BUFFER_SIZE];
        int read = 0;
        DataInputStream source = new DataInputStream(is);
        while ((remaining == -1) || (remaining > 0)) {
            read = source.read(buffer, 0, ((remaining > FileUtil.BUFFER_SIZE) || (remaining == -1)) ? FileUtil.BUFFER_SIZE : (int) remaining);
            if (read == -1) {
                if (length == -1) {
                    return;
                }
                throw new EOFException("stream reached eof");
            }
            if(read == 0) throw new IOException("stream returning 0 bytes");
            if(read != 0)
                crc.update(buffer, 0, read);
            destination.write(buffer, 0, read);
            if (remaining > 0)
                remaining -= read;
        }
        byte[] checksum = new byte[checksumLength()];
        source.readFully(checksum);
        byte[] myChecksum = Fields.intToBytes((int)crc.getValue());
        if(!Arrays.equals(checksum, myChecksum))
            throw new ChecksumFailedException();
    }

    @Override
    public void readAndChecksum(DataInput is, byte[] buf, int offset, int length)
            throws IOException, ChecksumFailedException {
        is.readFully(buf, offset, length);
        byte[] checksum = new byte[checksumLength()];
        is.readFully(checksum);
        if(!checkChecksum(buf, offset, length, checksum)) {
            Arrays.fill(buf, offset, offset+length, (byte)0);
            throw new ChecksumFailedException();
        }
    }

}
