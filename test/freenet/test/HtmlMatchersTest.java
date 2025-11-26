package freenet.test;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.StringDescription;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.Test;

import static freenet.test.HtmlMatchers.hasElement;
import static freenet.test.HtmlMatchers.hasTitle;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.any;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

public class HtmlMatchersTest {

	@Test
	public void hasTitleMatcherRecognizesIfNoTitleHasBeenSet() {
		assertThat(hasTitle(any(String.class)).matches(documentWithoutTitle), equalTo(false));
	}

	@Test
	public void hasTitleMatcherDescribesMismatch() {
		hasTitle(any(String.class)).describeMismatch(documentWithoutTitle, description);
		assertThat(description.toString(), equalTo("no title present"));
	}

	@Test
	public void hasTitleMatcherDescribesMismatchOfTitleMatcher() {
		hasTitle(nullValue()).describeMismatch(documentWithTitle, description);
		assertThat(description.toString(), equalTo("title was \"Test!\""));
	}

	@Test
	public void hasTitleMatcherRecognizesIfTitleHasBeenSet() {
		assertThat(hasTitle(any(String.class)).matches(documentWithTitle), equalTo(true));
	}

	@Test
	public void hasTitleMatcherDescribesItself() {
		hasTitle(any(String.class)).describeTo(description);
		assertThat(description.toString(), equalTo("has title matching an instance of java.lang.String"));
	}

	@Test
	public void titleMatcherIsNotConsultedIfNoTitleIsPresent() {
		Matcher<String> titleMatcher = mock(Matcher.class);
		hasTitle(titleMatcher).matches(documentWithoutTitle);
		verifyZeroInteractions(titleMatcher);
	}

	@Test
	public void titleMatcherIsConsultedIfTitleIsPresent() {
		Matcher<String> titleMatcher = mock(Matcher.class);
		hasTitle(titleMatcher).matches(documentWithTitle);
		verify(titleMatcher).matches(eq("Test!"));
	}

	@Test
	public void hasElementMatcherDoesNotMatchIfElementDoesNotExist() {
		assertThat(hasElement("some element").matches(documentWithTitle), equalTo(false));
	}

	@Test
	public void hasElementMatcherDescribesMismatchBecauseOfMissingElement() {
		hasElement("some element").describeMismatch(documentWithTitle, description);
		assertThat(description.toString(), equalTo("not found: \"some element\""));
	}

	@Test
	public void hasElementMatcherMatchesIfElementExists() {
		assertThat(hasElement("head title").matches(documentWithTitle), equalTo(true));
	}

	@Test
	public void hasElementMatcherDescribesItself() {
		hasElement("head title").describeTo(description);
		assertThat(description.toString(), equalTo("has element \"head title\""));
	}

	private final Description description = new StringDescription();
	private final Document documentWithoutTitle = Jsoup.parse("<html><head></head><body></body></html>");
	private final Document documentWithTitle = Jsoup.parse("<html><head><title>Test!</title></head><body></body></html>");

}
