/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.io.IOException;
import java.util.ArrayList;

import junit.framework.TestCase;
import freenet.node.FSParseException;
import freenet.support.SimpleFieldSet;

/**
 * Tests encoding and decoding of {@link FCPPluginMessage}s into the on-network format:
 * - Encodes them via {@link FCPPluginServerMessage}, which is the encoder for sending messages from
 *   a FCP server plugin to a client which is attached by network.<br>
 * - Decodes them via {@link FCPPluginClientMessage}, which is the decoder for decoding messages
 *   which we have received from a client which is attached by network.<br>
 * - Compares whether the decoded version matches the original {@link FCPPluginMessage}.<br><br>
 * 
 * Notice that this test is sort of an abuse: {@link FCPPluginClientMessage} is not meant to decode
 * {@link FCPPluginServerMessage}s as they are from the server, not from the client.<br>
 * It works because their format is very similar though.<br>
 * And it is the only way we have for testing encoding and decoding: There is no decoder for server
 * messages and no encoder for client messages because the current architecture of plugin FCP is to
 * always have the server plugin run locally in the same node as the FCP code and only allow clients
 * to be attached by network, not server plugins.<br>
 * See {@link FCPPluginConnectionImpl} for an overview of the architecture. 
 */
public final class FCPPluginMessageEncodeDecodeTest extends TestCase {

    /**
     * Creates different interesting types of {@link FCPPluginMessage}, whose actual encoding and
     * decoding is then tested using {@link #testEncodeDecode(FCPPluginMessage)}.
     */
    public final void testEncodeDecode() throws MessageInvalidException, IOException, FSParseException {
        ArrayList<FCPPluginMessage> messages = new ArrayList<FCPPluginMessage>();
        
        // Non-reply messages. Can either have a SimpleFieldSet, or a Bucket, or both. We don't use
        // Buckets because the parsing code for those is higher level code, so we only have to use
        // the constructor which creates a SimpleFieldSet and sets the Bucket to null.
        
        messages.add(FCPPluginMessage.construct());
        
        // Reply messages, with all possible allowed combinations of errorCode / errorMessage.
        
        messages.add(FCPPluginMessage.constructErrorReply(FCPPluginMessage.construct(),
                "TestErrorCode", "Test errorMessage"));
        messages.add(FCPPluginMessage.constructErrorReply(FCPPluginMessage.construct(),
                "TestErrorCode", null));
        messages.add(FCPPluginMessage.constructErrorReply(FCPPluginMessage.construct(),
                null, null));
        messages.add(FCPPluginMessage.constructSuccessReply(FCPPluginMessage.construct()));
        
        // Now the messages are created, but their SimpleFieldSet are empty. We test the
        // encode-decode cycle with various amount of data in the SFS.
        
        for(FCPPluginMessage message : messages) {
            // Non-reply messages cannot have an empty SFS and a null Bucket because then they have
            // no meaning at all.
            // (Reply messages can be empty because they still contain the boolean success)
            if(message.params != null && !message.isReplyMessage()) {
                message.params.putOverwrite("key1", "value1");
            }
            testEncodeDecode(message);
        }

        for(FCPPluginMessage message : messages) {
            if(message.params != null) {
                message.params.putOverwrite("key2", "value2");
            }
            testEncodeDecode(message);
        }
        
        for(FCPPluginMessage message : messages) {
            if(message.params != null) {
                message.params.putOverwrite("key3", "value3 with spaces");
            }
            testEncodeDecode(message);
        }
    }
    
    /**
     * @see FCPPluginMessageEncodeDecodeTest Explained at class-level JavaDoc of this class.
     */
    private final void testEncodeDecode(FCPPluginMessage message)
            throws MessageInvalidException, IOException, FSParseException {
        
        SimpleFieldSet encodedMessage
            = new FCPPluginServerMessage("testPlugin", message).getFieldSet();
        
        // The params have a different prefix in FCPPluginServerMessage and FCPPluginClientMessage.
        // So we have to rename them by removing the sub-SimpleFieldSet with the old prefix and
        // re-adding it with the new one.
        SimpleFieldSet params = encodedMessage.subset(FCPPluginServerMessage.PARAM_PREFIX);
        if(params != null) {
            encodedMessage.removeSubset(FCPPluginServerMessage.PARAM_PREFIX);
            encodedMessage.put(FCPPluginClientMessage.PARAM_PREFIX, params);
        }
        
        FCPPluginMessage decodedMessage
            = new FCPPluginClientMessage(encodedMessage).constructFCPPluginMessage();
 
        // Permissions are set by the FCPPluginConnectionImpl when the message is actually delivered
        assertEquals(null, decodedMessage.permissions);
        
        assertEquals(message.identifier, decodedMessage.identifier);
        
        // SimpleFieldSet offers no equals(). But its designed to be human readable, so we can
        // just encode them into Strings and compare those.
        if(message.params == null) {
            assertEquals(null, decodedMessage.params);
        } else {
            assertEquals(message.params.toOrderedString(), decodedMessage.params.toOrderedString());
        }
        
        if(message.data == null) {
            assertEquals(null, decodedMessage.data);
        } else {
            // Not implemented yet because the parsing of the data is higher level FCP code; i.e.
            // it is not implemented in FCPPluginClientMessage, but one of its parent classes.
            throw new UnsupportedOperationException("TODO: Implement");
        }
        
        assertEquals(message.success, decodedMessage.success);
        
        assertEquals(message.errorCode, decodedMessage.errorCode);
        
        assertEquals(message.errorMessage, decodedMessage.errorMessage);
    }

}
