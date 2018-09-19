package freenet.node;

import freenet.config.SubConfig;
import freenet.io.comm.Dispatcher;
import freenet.io.comm.MessageCore;
import freenet.store.CHKStore;
import freenet.store.PubkeyStore;
import freenet.store.SSKStore;

interface ProtectedNode extends Node {

    MessageCore getUSM();

    DNSRequester getDNSRequester();

    boolean ARKsEnabled();

    boolean enablePacketCoalescing();

    FailureTable getFailureTable();

    NodeDispatcher getDispatcher();

    boolean disableProbabilisticHTLs();

    boolean canWriteDatastoreInsert(short htl);

    NodeGetPubkey getGetPubkey();

    ProtectedNodeCrypto getInternalDarknetCrypto();

    void setDatabaseAwaitingPassword();

    DatabaseKey getDatabaseKey();

    boolean hasPanicked();

    PubkeyStore getOldPK();

    PubkeyStore getOldPKCache();

    PubkeyStore getOldPKClientCache();

    boolean enableULPRDataPropagation();

    boolean enablePerNodeFailureTables();

    boolean enableSwapping();

    boolean enableSwapQueueing();

    CHKStore getChkDatacache();
    CHKStore getChkDatastore();
    SSKStore getSskDatacache();
    SSKStore getSskDatastore();

    CHKStore getChkSlashdotCache();
    CHKStore getChkClientCache();
    SSKStore getSskSlashdotCache();
    SSKStore getSskClientCache();

    ProgramDirectory setupProgramDir(SubConfig installConfig,
                                     String cfgKey, String defaultValue, String shortdesc, String longdesc,
                                     SubConfig oldConfig) throws NodeInitException;
}
