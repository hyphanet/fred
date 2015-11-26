package freenet.clients.fcp;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

import org.junit.Test;

/**
 * Unit test for {@link FCPMessage}.
 *
 * @author <a href="mailto:david.roden@bietr.de">David Roden</a>
 */
public class FCPMessageTest {

    private static final String LIST_REQUEST_IDENTIFIER = "ListRequestIdentifier";
    private static final String IDENTIFIER = "identifier";
    private static final String MESSAGE_NAME = "SomeMessage";
    private static final String END_STRING = "End";

    private final FCPMessage originalMessage = mock(FCPMessage.class);

    @Test
    public void wrappingNullReturnsNull() {
        assertThat(FCPMessage.withListRequestIdentifier(null, LIST_REQUEST_IDENTIFIER), nullValue());
    }

    @Test
    public void wrappingMessageAddsIdentifier() {
        when(originalMessage.getFieldSet()).thenReturn(new SimpleFieldSet(true));
        FCPMessage wrappedMessage = FCPMessage.withListRequestIdentifier(originalMessage, IDENTIFIER);
        assertThat(wrappedMessage.getFieldSet().get(LIST_REQUEST_IDENTIFIER), is(IDENTIFIER));
    }

    @Test
    public void messageIsNotWrappedIfListRequestIdentifierIsNull() {
        assertThat(FCPMessage.withListRequestIdentifier(originalMessage, null), sameInstance(originalMessage));
    }

    @Test
    public void wrappedMessageDelegatesName() {
        when(originalMessage.getName()).thenReturn(MESSAGE_NAME);
        FCPMessage wrappedMessage = FCPMessage.withListRequestIdentifier(originalMessage, IDENTIFIER);
        assertThat(wrappedMessage.getName(), is(MESSAGE_NAME));
        verify(originalMessage).getName();
    }

    @Test
    public void wrappedMessageDelegatesRun() throws MessageInvalidException {
        FCPMessage wrappedMessage = FCPMessage.withListRequestIdentifier(originalMessage, IDENTIFIER);
        FCPConnectionHandler connectionHandler = mock(FCPConnectionHandler.class);
        Node node = mock(Node.class);
        wrappedMessage.run(connectionHandler, node);
        verify(originalMessage).run(connectionHandler, node);
    }

    @Test
    public void wrappedMessageDelegatesEndString() {
        FCPMessage wrappedMessage = FCPMessage.withListRequestIdentifier(originalMessage, IDENTIFIER);
        when(originalMessage.getEndString()).thenReturn(END_STRING);
        assertThat(wrappedMessage.getEndString(), is(END_STRING));
        verify(originalMessage).getEndString();
    }

    @Test
    public void wrappedMessageDelegatesSend() throws IOException {
        FCPMessage wrappedMessage = FCPMessage.withListRequestIdentifier(originalMessage, IDENTIFIER);
        OutputStream outputStream = mock(OutputStream.class);
        wrappedMessage.send(outputStream);
        verify(originalMessage).send(outputStream);
    }

}
