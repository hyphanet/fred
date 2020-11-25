package freenet.clients.http.utils;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.StringOption;
import freenet.support.MultiValueTable;
import freenet.support.api.StringCallback;

public class UriFilterProxyHeaderParserTest {

  // typical arguments
  @Test
  public void standardValuesParsedAsStandardPrefix()
      throws Exception {
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
  public void emptyArgumentsParsedAsStandardPrefix()
      throws Exception {
    testUriPrefixMatchesExpected(
        "",
        "",
        "",
        "",
        MultiValueTable.from(new String[]{}, new String[]{}),
        "http://127.0.0.1:8888");
  }

  @Test
  public void nullArgumentsParsedAsStandardPrefix()
      throws Exception {
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
  public void defaultPortParsedAsStandardPrefix()
      throws Exception {
    testUriPrefixMatchesExpected(
        "8888",
        "",
        "",
        "",
        MultiValueTable.from(new String[]{}, new String[]{}),
        "http://127.0.0.1:8888");
  }

  @Test
  public void defaultIpParsedAsStandardPrefix()
      throws Exception {
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
  public void allowedProxyHostIsUsedIfInSecondPositionOfBindTo()
      throws Exception {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1,foo",
        "",
        "",
        MultiValueTable.from(new String[]{ "x-forwarded-host" }, new String[]{ "foo" }),
        "http://foo");
  }

  @Test
  public void allowedProxyHostIsUsedIfInFirstPositionOfBindTo()
      throws Exception {
    testUriPrefixMatchesExpected(
        "8888",
        "foo,127.0.0.1",
        "",
        "",
        MultiValueTable.from(new String[]{ "x-forwarded-host" }, new String[]{ "foo:8888" }),
        "http://foo:8888");
  }

  @Test
  public void allowedProxyHostWithNonstandardPortIsUsed()
      throws Exception {
    testUriPrefixMatchesExpected(
        "8889",
        "127.0.0.1,foo",
        "",
        "",
        MultiValueTable.from(new String[]{ "x-forwarded-host" }, new String[]{ "foo:8889" }),
        "http://foo:8889");
  }

  @Test
  public void httpsProtocolFromProxyIsUsed()
      throws Exception {
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
  public void allowedUriHostIsUsed() throws Exception {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1,foo",
        "",
        "foo",
        MultiValueTable.from(new String[]{}, new String[]{}),
        "http://foo");
  }

  @Test
  public void allowedUriHostWithStandardPortAreUsed()
      throws Exception {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1,foo",
        "",
        "foo:8888",
        MultiValueTable.from(new String[]{}, new String[]{}),
        "http://foo:8888");
  }

  @Test
  public void allowedUriAndNonstandardPortAreUsed()
      throws Exception {
    testUriPrefixMatchesExpected(
        "8889",
        "127.0.0.1,foo",
        "",
        "foo:8889",
        MultiValueTable.from(new String[]{}, new String[]{}),
        "http://foo:8889");
  }

  @Test
  public void httpsProtocolFromUriIsUsed()
      throws Exception {
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
  public void disallowedProxyHostHeaderIsIgnored()
      throws Exception {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1",
        "",
        "",
        MultiValueTable.from(new String[]{ "x-forwarded-host" }, new String[]{ "foo" }),
        "http://127.0.0.1:8888");
  }

  @Test
  public void disallowedProxyProtocolIsIgnored()
      throws Exception {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1",
        "",
        "",
        MultiValueTable.from(new String[]{ "x-forwarded-proto" }, new String[]{ "catchme" }),
        "http://127.0.0.1:8888");
  }

  @Test
  public void disallowedProxyHostAndPortAreIgnored()
      throws Exception {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1",
        "",
        "foo:8888",
        MultiValueTable.from(new String[]{ "x-forwarded-host" }, new String[]{ "foo:8888" }),
        "http://127.0.0.1:8888");
  }

  @Test
  public void disallowedProxyWithStandardPortIsIgnoredIfPortIsNotStandardAndHostNotAllowed()
      throws Exception {
    testUriPrefixMatchesExpected(
        "8889",
        "127.0.0.1",
        "",
        "",
        MultiValueTable.from(new String[]{ "x-forwarded-host" }, new String[]{ "foo:8888" }),
        "http://127.0.0.1:8889");
  }

  @Test
  public void disallowedProxyWithStandardPortIsIgnoredIfPortIsNotStandard()
      throws Exception {
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
  public void disallowedUriHostIsIgnored()
      throws Exception {
    testUriPrefixMatchesExpected(
        "8888",
        "127.0.0.1",
        "",
        "foo",
        MultiValueTable.from(new String[]{ "x-forwarded-host" }, new String[]{ "foo" }),
        "http://127.0.0.1:8888");
  }

  @Test
  public void disallowedUriHostWithStandardPortIsIgnored()
      throws Exception {
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
  public void disallowedUriWithAllowedHostButDisallowedPortIsIgnored()
      throws Exception {
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
      String resultUriPrefix) throws Exception {
    String schemeHostAndPort = UriFilterProxyHeaderParser.parse(
        fakePortOption(fProxyPort),
        fakeBindToOption(fProxyBindTo),
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

  private StringOption fakeBindToOption(String value)
      throws Exception {
    StringOption option = new StringOption(
        new Config().createSubConfig("fake"),
        "bindTo",
        "127.0.0.1",
        0,
        false,
        false,
        "",
        "",
        new DummyStringCallback());
    option.setValue(value);
    return option;
  }

  private StringOption fakePortOption(String value)
      throws Exception {
    StringOption option = new StringOption(
        new Config().createSubConfig("fake"),
        "port",
        "8888",
        0,
        false,
        false,
        "",
        "",
        new DummyStringCallback());
    option.setValue(value);
    return option;
  }

  private class DummyStringCallback extends StringCallback {

    @Override
    public String get() {
      return null;
    }

    @Override
    public void set(String val) {

    }
  }

}
