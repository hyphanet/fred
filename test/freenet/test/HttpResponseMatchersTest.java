package freenet.test;

import org.hamcrest.Description;
import org.hamcrest.Matchers;
import org.hamcrest.StringDescription;
import org.junit.Test;

import static freenet.test.HttpResponseMatchers.hasBody;
import static freenet.test.HttpResponseMatchers.hasHeader;
import static freenet.test.HttpResponseMatchers.hasHeaders;
import static freenet.test.HttpResponseMatchers.hasNoBody;
import static freenet.test.HttpResponseMatchers.hasStatus;
import static freenet.test.HttpResponseMatchers.hasStringBody;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anything;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

public class HttpResponseMatchersTest {

	@Test
	public void hasStatusMatcherIsDescribedAppropriately() {
		hasStatus(200).describeTo(description);
		assertThat(description.toString(), startsWith("status is <200>"));
	}

	@Test
	public void hasStatusMatcherMatchesWhenStatusCodeIsTheRightOne() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		assertThat(hasStatus(200).matches(httpResponse), equalTo(true));
	}

	@Test
	public void hasStatusMatcherDoesNotMatchIfStatusCodeIsDifferent() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		assertThat(hasStatus(201).matches(httpResponse), equalTo(false));
	}

	@Test
	public void hasStatusMatcherDescribesMismatchCorrectly() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		hasStatus(201).describeMismatch(httpResponse, description);
		assertThat(description.toString(), startsWith("status was <200>"));
	}


	@Test
	public void hasStatusMatcherWithIntMatcherIsDescribedAppropriately() {
		hasStatus(allOf(greaterThanOrEqualTo(200), lessThan(300))).describeTo(description);
		assertThat(description.toString(), startsWith("status is (a value equal to or greater than <200> and a value less than <300>)"));
	}

	@Test
	public void hasStatusMatcherWithIntMatcherMatchesWhenStatusCodeIsTheRightOne() {
		HttpResponse httpResponse = new HttpResponse(238, "");
		assertThat(hasStatus(allOf(greaterThanOrEqualTo(200), lessThan(300))).matches(httpResponse), equalTo(true));
	}

	@Test
	public void hasStatusMatcherWithIntMatcherDoesNotMatchIfStatusCodeIsDifferent() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		assertThat(hasStatus(allOf(greaterThanOrEqualTo(300), lessThan(400))).matches(httpResponse), equalTo(false));
	}

	@Test
	public void hasStatusMatcherWithIntMatcherDescribesMismatchCorrectly() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		hasStatus(equalTo(201)).describeMismatch(httpResponse, description);
		assertThat(description.toString(), equalTo("status was <200>"));
	}

	@Test
	public void hasStatusMatcherWithTextMatcherDescribesMatcherCorrectly() {
		hasStatus(anything(), containsString("OK")).describeTo(description);
		assertThat(description.toString(), equalTo("status is ANYTHING and status text is a string containing \"OK\""));
	}

	@Test
	public void hasStatusMatcherWithTextMatcherMatchesWhenTheStatusTextIsRight() {
		HttpResponse httpResponse = new HttpResponse(200, "Request Complete");
		assertThat(hasStatus(anything(), equalTo("Request Complete")).matches(httpResponse), equalTo(true));
	}

	@Test
	public void hasStatusMatcherWithTextMatcherDoesNotMatchWhenTheStatusTextIsNotRight() {
		HttpResponse httpResponse = new HttpResponse(200, "Request Complete");
		assertThat(hasStatus(anything(), equalTo("OK")).matches(httpResponse), equalTo(false));
	}

	@Test
	public void hasStatusMatcherWithTextDescribesMismatchCorrectly() {
		HttpResponse httpResponse = new HttpResponse(200, "Request Complete");
		hasStatus(anything(), equalTo("OK")).describeMismatch(httpResponse, description);
		assertThat(description.toString(), equalTo("status text was \"Request Complete\""));
	}

	@Test
	public void hasHeaderMatcherIsDescribedAppropriately() {
		hasHeader("Some-Header", containsInAnyOrder(equalTo("Value1"), containsString("Value2"))).describeTo(description);
		assertThat(description.toString(), equalTo("header \"Some-Header\" matches iterable with items [\"Value1\", a string containing \"Value2\"] in any order"));
	}

	@Test
	public void hasHeaderMatcherMatchesWhenHeaderContainsCorrectValues() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		httpResponse.addHeader("Some-Header", "this is Value2.");
		httpResponse.addHeader("Some-Header", "Value1");
		assertThat(hasHeader("Some-Header", containsInAnyOrder(equalTo("Value1"), containsString("Value2"))).matches(httpResponse), equalTo(true));
	}

	@Test
	public void hasHeaderMatcherDoesNotMatchWhenNoHeaderExists() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		assertThat(hasHeader("Some-Header", containsInAnyOrder(equalTo("Value1"), containsString("Value2"))).matches(httpResponse), equalTo(false));
	}

	@Test
	public void hasHeaderMatcherDoesNotMatchWhenHeaderHasTooFewValues() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		httpResponse.addHeader("Some-Header", "Value1");
		assertThat(hasHeader("Some-Header", containsInAnyOrder(equalTo("Value1"), containsString("Value2"))).matches(httpResponse), equalTo(false));
	}

	@Test
	public void hasHeaderMatcherDoesNotMatchWhenHeaderHasTooManyValues() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		httpResponse.addHeader("Some-Header", "this is Value2.");
		httpResponse.addHeader("Some-Header", "Value1");
		httpResponse.addHeader("Some-Header", "Value3");
		assertThat(hasHeader("Some-Header", containsInAnyOrder(equalTo("Value1"), containsString("Value2"))).matches(httpResponse), equalTo(false));
	}

	@Test
	public void hasHeaderMatcherDoesNotMatchWhenValuesAreNotCorrect() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		httpResponse.addHeader("Some-Header", "this is Value1");
		httpResponse.addHeader("Some-Header", "Value2");
		assertThat(hasHeader("Some-Header", containsInAnyOrder(equalTo("Value1"), containsString("Value2"))).matches(httpResponse), equalTo(false));
	}

	@Test
	public void hasHeaderMatcherDescribesMismatchCorrectly() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		httpResponse.addHeader("Some-Header", "this is Value1");
		httpResponse.addHeader("Some-Header", "Value2");
		hasHeader("Some-Header", containsInAnyOrder(equalTo("Value1"), containsString("Value2"))).describeMismatch(httpResponse, description);
		assertThat(description.toString(), equalTo("header \"Some-Header\" was <[this is Value1, Value2]>"));
	}

	@Test
	public void hasHeadersMatcherIsDescribedCorrectly() {
		hasHeaders(Matchers.either(hasItem("Header1")).or(hasItem("Header2"))).describeTo(description);
		assertThat(description.toString(), equalTo("headers match (a collection containing \"Header1\" or a collection containing \"Header2\")"));
	}

	@Test
	public void hasHeadersMatcherMatchersWhenTheCorrectHeadersExist() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		httpResponse.addHeader("Header1", "Value1");
		httpResponse.addHeader("Header2", "Value2");
		assertThat(hasHeaders(allOf(hasItem(equalToIgnoringCase("header1")), hasItem(equalToIgnoringCase("HeAdEr2")), not(hasItem(equalToIgnoringCase("Header3"))))).matches(httpResponse), equalTo(true));
	}

	@Test
	public void hasHeadersMatcherDoesNotMatchIfHeadersDoNotMatch() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		httpResponse.addHeader("Header1", "Value1");
		httpResponse.addHeader("Header2", "Value2");
		assertThat(hasHeaders(allOf(hasItem(equalToIgnoringCase("Header1")), hasItem(equalToIgnoringCase("Header2")), hasItem(equalToIgnoringCase("Header3")))).matches(httpResponse), equalTo(false));
	}

	@Test
	public void hasHeadersMatcherDescribesMismatchCorrectly() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		httpResponse.addHeader("Header", "Value");
		hasHeaders(hasItem("No-Header")).describeMismatch(httpResponse, description);
		assertThat(description.toString(), equalTo("headers were <[header]>"));
	}

	@Test
	public void hasBodyMatcherIsDescribedCorrectly() {
		hasBody(equalTo(new byte[] { 1, 2, 3 })).describeTo(description);
		assertThat(description.toString(), equalTo("body is [<1b>, <2b>, <3b>]"));
	}

	@Test
	public void hasBodyMatcherMatchesWhenTheBodyIsCorrect() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		httpResponse.setBody(new byte[] { 1, 2, 3 });
		assertThat(hasBody(equalTo(new byte[] { 1, 2, 3 })).matches(httpResponse), equalTo(true));
	}

	@Test
	public void hasBodyMatcherDoesNotMatchIfThereIsNoBody() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		assertThat(hasBody(equalTo(new byte[] { 1, 2, 3 })).matches(httpResponse), equalTo(false));
	}

	@Test
	public void hasBodyMatcherDescribesMismatchCorrectlyIfThereIsNoBody() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		hasBody(equalTo(new byte[] { 1, 2, 3 })).describeMismatch(httpResponse, description);
		assertThat(description.toString(), equalTo("body was missing"));
	}

	@Test
	public void hasBodyMatcherDoesNotMatchIfBodyDoesNotMatchIf() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		httpResponse.setBody(new byte[] { 4, 5, 6 });
		assertThat(hasBody(equalTo(new byte[] { 1, 2, 3 })).matches(httpResponse), equalTo(false));
	}

	@Test
	public void hasBodyMatcherDescribesMismatchCorrectly() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		httpResponse.setBody(new byte[] { 4, 5, 6 });
		hasBody(equalTo(new byte[] { 1, 2, 3 })).describeMismatch(httpResponse, description);
		assertThat(description.toString(), equalTo("body was [<4b>, <5b>, <6b>]"));
	}

	@Test
	public void hasStringBodyMatcherIsDescribedCorrectly() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		httpResponse.setBody("This is a test body.".getBytes(UTF_8));
		hasStringBody(containsString("test")).describeTo(description);
		assertThat(description.toString(), equalTo("body matches a string containing \"test\""));
	}

	@Test
	public void hasStringBodyMatchesWhenBodyIsCorrect() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		httpResponse.setBody("This is a test body.".getBytes(UTF_8));
		assertThat(hasStringBody(containsString("test")).matches(httpResponse), equalTo(true));
	}

	@Test
	public void hasStringBodyDoesNotMatchIfThereIsNoBody() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		assertThat(hasStringBody(containsString("test")).matches(httpResponse), equalTo(false));
	}

	@Test
	public void hasStringBodyDescribesMismatchCorrectlyIfThereIsNoBody() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		hasStringBody(containsString("test")).describeMismatch(httpResponse, description);
		assertThat(description.toString(), equalTo("body was missing"));
	}

	@Test
	public void hasStringBodyDoesNotMatchIfBodyIsDifferent() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		httpResponse.setBody("This is a test body.".getBytes(UTF_8));
		assertThat(hasStringBody(containsString("no match")).matches(httpResponse), equalTo(false));
	}

	@Test
	public void hasStringBodyDescribesMismatchCorrectly() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		httpResponse.setBody("This is a test body.".getBytes(UTF_8));
		hasStringBody(containsString("no match")).describeMismatch(httpResponse, description);
		assertThat(description.toString(), equalTo("body was \"This is a test body.\""));
	}

	@Test
	public void hasNoBodyMatcherIsDescribedCorrectly() {
		hasNoBody().describeTo(description);
		assertThat(description.toString(), equalTo("has no body"));
	}

	@Test
	public void hasNoBodyMatcherMatchesIfThereIsNoBody() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		assertThat(hasNoBody().matches(httpResponse), equalTo(true));
	}

	@Test
	public void hasNoBodyMatcherDoesNotMatchIfThereIsABody() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		httpResponse.setBody(new byte[] { 4, 5, 6 });
		assertThat(hasNoBody().matches(httpResponse), equalTo(false));
	}

	@Test
	public void hasNoBodyMatcherDescribesMismatchCorrectly() {
		HttpResponse httpResponse = new HttpResponse(200, "");
		httpResponse.setBody(new byte[] { 4, 5, 6 });
		hasNoBody().describeMismatch(httpResponse, description);
		assertThat(description.toString(), equalTo("body was [<4b>, <5b>, <6b>]"));
	}

	private final Description description = new StringDescription();

}
