/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import freenet.crypt.HashResult;

import freenet.support.api.Bucket;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;

class CompressionOutput {
    final Bucket data;
    final COMPRESSOR_TYPE bestCodec;
    final HashResult[] hashes;

    public CompressionOutput(Bucket bestCompressedData, COMPRESSOR_TYPE bestCodec2, HashResult[] hashes) {
        this.data = bestCompressedData;
        this.bestCodec = bestCodec2;
        this.hashes = hashes;
    }
}
