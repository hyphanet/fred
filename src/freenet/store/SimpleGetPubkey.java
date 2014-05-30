/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.store;

//~--- non-JDK imports --------------------------------------------------------

import freenet.crypt.DSAPublicKey;

import freenet.support.HexUtil;
import freenet.support.Logger;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;

public class SimpleGetPubkey implements GetPubkey {
    final PubkeyStore store;

    public SimpleGetPubkey(PubkeyStore store) {
        this.store = store;
    }

    @Override
    public DSAPublicKey getKey(byte[] hash, boolean canReadClientCache, boolean forULPR, BlockMetadata meta) {
        try {
            return store.fetch(hash, false, false, meta);
        } catch (IOException e) {
            Logger.error(this, "Caught " + e + " fetching pubkey for " + HexUtil.bytesToHex(hash));

            return null;
        }
    }

    @Override
    public void cacheKey(byte[] hash, DSAPublicKey key, boolean deep, boolean canWriteClientCache,
                         boolean canWriteDatastore, boolean forULPR, boolean writeLocalToDatastore) {
        try {
            store.put(hash, key, false);
        } catch (IOException e) {
            Logger.error(this, "Caught " + e + " storing pubkey for " + HexUtil.bytesToHex(hash));
        }
    }
}
