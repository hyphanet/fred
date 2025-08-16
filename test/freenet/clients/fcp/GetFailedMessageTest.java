package freenet.clients.fcp;

import freenet.client.FetchException;
import org.junit.Test;

import static freenet.client.FetchException.FetchExceptionMode.INTERNAL_ERROR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class GetFailedMessageTest {

	@Test
	public void getFailedMessageWithoutGlobalFlagDoesNotContainGlobalFlagInFieldSet() {
		GetFailedMessage message = new GetFailedMessage(fetchException, "TestMessage", false);
		assertThat(message.getFieldSet().get("Global"), anyOf(nullValue(), equalTo("false")));
	}

	@Test
	public void getFailedMessageWithGlobalFlagContainsGlobalFlagInFieldSet() {
		GetFailedMessage message = new GetFailedMessage(fetchException, "TestMessage", true);
		assertThat(message.getFieldSet().get("Global"), equalTo("true"));
	}

	private final FetchException fetchException = new FetchException(INTERNAL_ERROR);

}
