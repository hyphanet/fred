/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.events;

public class FinishedCompressionEvent implements ClientEvent {

    static final int code = 0x09;
    
    /** Codec, -1 = uncompressed */
    public final int codec;
    /** Original size */
    public final long originalSize;
    /** Compressed size */
    public final long compressedSize;

    public FinishedCompressionEvent(int codec, long origSize, long compressedSize) {
        this.codec = codec;
        this.originalSize = origSize;
        this.compressedSize = compressedSize;
    }

    @Override
    public String getDescription() {
        return "Compressed data: codec="+codec+", origSize="+originalSize+", compressedSize="+compressedSize;
    }

    @Override
    public int getCode() {
        return code;
    }
    
}
