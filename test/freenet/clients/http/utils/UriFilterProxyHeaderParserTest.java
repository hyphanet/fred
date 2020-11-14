package freenet.clients.http.utils;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import freenet.config.Config;
import freenet.config.Option;
import freenet.config.StringOption;
import freenet.config.SubConfig;
import freenet.support.MultiValueTable;

public class UriFilterProxyHeaderParserTest {

  // typical arguments
  @Test
  public void standardValuesParsedAsStandardPrefix() {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1",
        "",
        "",
        MultiValueTable.from(new String[]{}, new String[]{}),
        "http://127.0.0.1:8888");
  }

  // empty arguments result in plain http:// prefix
  @Test
  public void emptyArgumentsParsedAsStandardPrefix() {
    testUriPrefixMatchesExpected(
        "",
        "",
        "",
        "",
        MultiValueTable.from(new String[]{}, new String[]{}),
        "http://127.0.0.1:8888");
  }

  @Test
  public void nullArgumentsParsedAsStandardPrefix() {
    testUriPrefixMatchesExpected(
        "",
        "",
        null,
        null,
        MultiValueTable.from(new String[]{}, new String[]{}),
        "http://127.0.0.1:8888");
  }

  // sanity checks for defaults
  @Test
  public void defaultPortParsedAsStandardPrefix() {
    testUriPrefixMatchesExpected(
        "8888",
        "",
        "",
        "",
        MultiValueTable.from(new String[]{}, new String[]{}),
        "http://127.0.0.1:8888");
  }

  @Test
  public void defaultIpParsedAsStandardPrefix() {
    testUriPrefixMatchesExpected(
        "",
        "127.0.0.1",
        "",
        "",
        MultiValueTable.from(new String[]{}, new String[]{}),
        "http://127.0.0.1:8888");
  }

  // taking proxy values
  @Test
  public void allowedProxyHostIsUsedIfInSecondPositionOfBindTo() {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1,foo",
        "",
        "",
        MultiValueTable.from(new String[]{ "x-forwarded-host" }, new String[]{ "foo" }),
        "http://foo");
  }

  @Test
  public void allowedProxyHostIsUsedIfInFirstPositionOfBindTo() {
    testUriPrefixMatchesExpected(
        "8888",
        "foo,127.0.0.1",
        "",
        "",
        MultiValueTable.from(new String[]{ "x-forwarded-host" }, new String[]{ "foo:8888" }),
        "http://foo:8888");
  }

  @Test
  public void allowedProxyHostWithNonstandardPortIsUsed() {
    testUriPrefixMatchesExpected(
        "8889",
        "127.0.0.1,foo",
        "",
        "",
        MultiValueTable.from(new String[]{ "x-forwarded-host" }, new String[]{ "foo:8889" }),
        "http://foo:8889");
  }

  @Test
  public void httpsProtocolFromProxyIsUsed() {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1",
        "",
        "",
        MultiValueTable.from(new String[]{ "x-forwarded-proto" }, new String[]{ "https" }),
        "https://127.0.0.1:8888");
  }

  // taking uri values
  @Test
  public void allowedUriHostIsUsed() {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1,foo",
        "",
        "foo",
        MultiValueTable.from(new String[]{}, new String[]{}),
        "http://foo");
  }

  @Test
  public void allowedUriHostWithStandardPortAreUsed() {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1,foo",
        "",
        "foo:8888",
        MultiValueTable.from(new String[]{}, new String[]{}),
        "http://foo:8888");
  }

  @Test
  public void allowedUriAndNonstandardPortAreUsed() {
    testUriPrefixMatchesExpected(
        "8889",
        "127.0.0.1,foo",
        "",
        "foo:8889",
        MultiValueTable.from(new String[]{}, new String[]{}),
        "http://foo:8889");
  }

  @Test
  public void httpsProtocolFromUriIsUsed() {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1",
        "https",
        "",
        MultiValueTable.from(new String[]{}, new String[]{}),
        "https://127.0.0.1:8888");
  }

  // ignored header values
  @Test
  public void disallowedProxyHostHeaderIsIgnored() {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1",
        "",
        "",
        MultiValueTable.from(new String[]{ "x-forwarded-host" }, new String[]{ "foo" }),
        "http://127.0.0.1:8888");
  }

  @Test
  public void disallowedProxyProtocolIsIgnored() {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1",
        "",
        "",
        MultiValueTable.from(new String[]{ "x-forwarded-proto" }, new String[]{ "catchme" }),
        "http://127.0.0.1:8888");
  }

  @Test
  public void disallowedProxyHostAndPortAreIgnored() {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1",
        "",
        "foo:8888",
        MultiValueTable.from(new String[]{ "x-forwarded-host" }, new String[]{ "foo:8888" }),
        "http://127.0.0.1:8888");
  }

  @Test
  public void disallowedProxyWithStandardPortIsIgnoredIfPortIsNotStandardAndHostNotAllowed() {
    testUriPrefixMatchesExpected(
        "8889",
        "127.0.0.1",
        "",
        "",
        MultiValueTable.from(new String[]{ "x-forwarded-host" }, new String[]{ "foo:8888" }),
        "http://127.0.0.1:8889");
  }

  @Test
  public void disallowedProxyWithStandardPortIsIgnoredIfPortIsNotStandard() {
    testUriPrefixMatchesExpected(
        "8889",
        "127.0.0.1",
        "",
        "",
        MultiValueTable.from(
            new String[]{ "x-forwarded-host" },
            new String[]{ "127.0.0.1:8888" }),
        "http://127.0.0.1:8889");
  }

  // ignored uri values
  // no such bindToHost
  @Test
  public void disallowedUriHostIsIgnored() {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1",
        "",
        "foo",
        MultiValueTable.from(new String[]{ "x-forwarded-host" }, new String[]{ "foo" }),
        "http://127.0.0.1:8888");
  }

  @Test
  public void disallowedUriHostWithStandardPortIsIgnored() {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1",
        "",
        "foo:8888",
        MultiValueTable.from(new String[]{ "x-forwarded-host" }, new String[]{ "foo:8888" }),
        "http://127.0.0.1:8888");
  }

  // port mismatch
  @Test
  public void disallowedUriWithAllowedHostButDisallowedPortIsIgnored() {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1,foo",
        "",
        "foo:8889",
        MultiValueTable.from(new String[]{ "x-forwarded-host" }, new String[]{ "foo:8889" }),
        "http://127.0.0.1:8888");
  }

  private void testUriPrefixMatchesExpected(
      String fProxyPort,
      String fProxyBindTo,
      String uriScheme,
      String uriHost,
      MultiValueTable<String, String> headers,
      String resultUriPrefix) {
    String schemeHostAndPort = UriFilterProxyHeaderParser.parse(
        fakeOption(fProxyPort),
        fakeOption(fProxyBindTo),
        uriScheme,
        uriHost,
        headers)
        .toString();
    assertTrue(
        String.format(
            "schemeHostAndPort %s does not match expected %s; portConfig=\"%s\", bindTo=\"%s\", uriScheme=\"%s\", uriHost=\"%s\", headers=%s, expected=\"%s\"",
            schemeHostAndPort,
            resultUriPrefix,
            fProxyPort,
            fProxyBindTo,
            uriScheme,
            uriHost,
            headers,
            resultUriPrefix),
        schemeHostAndPort.equals(resultUriPrefix));

  }

  private StringOption fakeOption(String value) {
    return new StringOption(
        new Config().createSubConfig("fake"),
        "port",
        value,
        0,
        false,
        false,
        null,
        null,
        null);
  }

}
