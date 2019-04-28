/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package freenet.node;

import freenet.crypt.DummyRandomSource;
import freenet.crypt.ECDSA;
import freenet.crypt.RandomSource;
import freenet.crypt.SHA256;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.support.Base64;
import freenet.support.SimpleFieldSet;
import junit.framework.TestCase;

/**
 *
 * @author martin
 */
public class PeerNodeTest extends TestCase {
    
    class TestPeerNode extends PeerNode {
        
        public boolean peersWritten;
        
        public TestPeerNode(SimpleFieldSet fs, ProtectedNode node2, NodeCrypto crypto, boolean fromLocal)
                throws FSParseException, PeerParseException, ReferenceSignatureVerificationException, PeerTooOldException {
            super(fs, node2, crypto, fromLocal);
        }

        @Override
        boolean dontKeepFullFieldSet() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        protected void maybeClearPeerAddedTimeOnRestart(long now) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        protected void writePeers() {
            peersWritten = true;
        }

        @Override
        protected void maybeClearPeerAddedTimeOnConnect() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public PeerNodeStatus getStatus(boolean noHeavy) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        protected boolean shouldExportPeerAddedTime() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public boolean isDarknet() {
            return true;
        }

        @Override
        public boolean isOpennet() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public boolean isOpennetForNoderef() {
            return false;
        }

        @Override
        public boolean isSeed() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public boolean recordStatus() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public void onSuccess(boolean insert, boolean ssk) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public boolean isRealConnection() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public boolean canAcceptAnnouncements() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public void fatalTimeout() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public boolean shallWeRouteAccordingToOurPeersLocation(int htl) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }
    }
    
    public void testInitializationForDarknetOnly() {

       class InstrumentedNode extends StubNode {

          DummyRandomSource mRNG = new DummyRandomSource();

          @Override
          public RandomSource getRNG() {
            return mRNG;
          }

       }
       
       class InstrumentedNodeCrypto extends StubNodeCrypto {

          byte[] dummyIdentity;
          FNPPacketMangler mOutgoingMangler;

          public InstrumentedNodeCrypto() {
             byte count = 0; 
             dummyIdentity = new byte[NodeCrypto.IDENTITY_LENGTH];
             for (int i = 0; i < NodeCrypto.IDENTITY_LENGTH; i++) {
               dummyIdentity[i] = count;
               count++;
             }
          }

          @Override
          public byte[] getIdentityHash() {
             return SHA256.digest(dummyIdentity);
          }

          @Override
          public byte[] getIdentityHashHash() {
             return SHA256.digest(getIdentityHash());
          }
          
          public void setPacketMangler(FNPPacketMangler mangler) {
              mOutgoingMangler = mangler;
          }
          
          @Override
           public FNPPacketMangler getPacketMangler() {
               return mOutgoingMangler;
           }
       }

        SimpleFieldSet fs = new SimpleFieldSet(true);
        InstrumentedNode node = new InstrumentedNode();
        StubUdpSocketHandler socket = new StubUdpSocketHandler();
        InstrumentedNodeCrypto nodeCrypto = new InstrumentedNodeCrypto();
        StubFNPPacketMangler mangler = new StubFNPPacketMangler(node, nodeCrypto, socket);
        nodeCrypto.setPacketMangler(mangler);
        byte[] idBytes = new byte[NodeCrypto.IDENTITY_LENGTH];
        
        try {
            
            fs.putSingle("version", "Fred,0.7,1.0,0001");
            fs.put("auth.negTypes", new int[] { 10 }); // Taken from FNPPacketMangler.supportedNegTypes
            
            // Add subset for ECDSA
            ECDSA dummyKey = new ECDSA(ECDSA.Curves.P256);
            fs.put("ecdsa", dummyKey.asFieldSet(true));
            
            // Add identity
            fs.putSingle("identity", Base64.encode(idBytes));
            
            TestPeerNode peerNode = new TestPeerNode(fs, node, nodeCrypto, true);
            
            assertTrue(peerNode.peersWritten);
        } catch (Exception e) {
            e.printStackTrace();
            assertTrue(false);
        }
        
    }
    
}
