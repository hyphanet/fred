package freenet.clients.http.utils;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import junit.framework.TestCase;

import freenet.support.MultiValueTable;

@RunWith(Parameterized.class)
public class UriFilterProxyHeaderParserTest extends TestCase {

  private final String fProxyPort;
  private final String fProxyBindTo;
  private final String uriScheme;
  private final String uriHost;
  private final MultiValueTable<String, String> headers;
  private String resultUriPrefix;

  @Parameterized.Parameters(
      name = "{index}: portConfig=\"{0}\", bindTo=\"{1}\", uriScheme=\"{2}\", uriHost=\"{3}\", headers={4}, expected=\"{5}\"")
  public static Collection constructorParametersAndResult () {
    return Arrays.asList(new Object[][] {
        // typical arguments
        {"8888", "127.0.0.1", "", "", MultiValueTable.from(new String[] {}, new String[] {}), "http://127.0.0.1:8888"},
        // empty arguments result in plain http:// prefix
        {"", "", "", "", MultiValueTable.from(new String[] {}, new String[] {}), "http://127.0.0.1:8888"},
        {"", "", null, null, MultiValueTable.from(new String[] {}, new String[] {}), "http://127.0.0.1:8888"},
        // sanity checks for defaults
        {"8888", "", "", "", MultiValueTable.from(new String[] {}, new String[] {}), "http://127.0.0.1:8888"},
        {"", "127.0.0.1", "", "", MultiValueTable.from(new String[] {}, new String[] {}), "http://127.0.0.1:8888"},
        // taking proxy values
        {"8888", "127.0.0.1,foo", "", "", MultiValueTable.from(new String[] {"x-forwarded-host"}, new String[] {"foo"}), "http://foo"},
        {"8888", "foo,127.0.0.1", "", "", MultiValueTable.from(new String[] {"x-forwarded-host"}, new String[] {"foo:8888"}), "http://foo:8888"},
        {"8889", "127.0.0.1,foo", "", "", MultiValueTable.from(new String[] {"x-forwarded-host"}, new String[] {"foo:8889"}), "http://foo:8889"},
        {"8888", "127.0.0.1", "", "", MultiValueTable.from(new String[] {"x-forwarded-proto"}, new String[] {"https"}), "https://127.0.0.1:8888"},
        // taking uri values
        {"8888", "127.0.0.1,foo", "", "foo", MultiValueTable.from(new String[] {"x-forwarded-host"}, new String[] {"foo"}), "http://foo"},
        {"8888", "127.0.0.1,foo", "", "foo:8888", MultiValueTable.from(new String[] {"x-forwarded-host"}, new String[] {"foo:8888"}), "http://foo:8888"},
        {"8889", "127.0.0.1,foo", "", "foo:8889", MultiValueTable.from(new String[] {"x-forwarded-host"}, new String[] {"foo:8889"}), "http://foo:8889"},
        {"8888", "127.0.0.1", "https", "", MultiValueTable.from(new String[] {"x-forwarded-proto"}, new String[] {"https"}), "https://127.0.0.1:8888"},
        // ignored header values
        {"8888", "127.0.0.1", "", "", MultiValueTable.from(new String[] {"x-forwarded-host"}, new String[] {"foo"}), "http://127.0.0.1:8888"},
        {"8888", "127.0.0.1", "", "", MultiValueTable.from(new String[] {"x-forwarded-proto"}, new String[] {"catchme"}), "http://127.0.0.1:8888"},
        {"8888", "127.0.0.1", "", "foo:8888", MultiValueTable.from(new String[] {"x-forwarded-host"}, new String[] {"foo:8888"}), "http://127.0.0.1:8888"},
        {"8889", "127.0.0.1", "", "", MultiValueTable.from(new String[] {"x-forwarded-host"}, new String[] {"foo:8888"}), "http://127.0.0.1:8889"},
        // ignored uri values
        // no such bindToHost
        {"8888", "127.0.0.1", "", "foo", MultiValueTable.from(new String[] {"x-forwarded-host"}, new String[] {"foo"}), "http://127.0.0.1:8888"},
        {"8888", "127.0.0.1", "", "foo:8888", MultiValueTable.from(new String[] {"x-forwarded-host"}, new String[] {"foo:8888"}), "http://127.0.0.1:8888"},
        // port mismatch
        {"8888", "127.0.0.1,foo", "", "foo:8889", MultiValueTable.from(new String[] {"x-forwarded-host"}, new String[] {"foo:8889"}), "http://127.0.0.1:8888"},
        {"8888", "127.0.0.1", "https", "", MultiValueTable.from(new String[] {"x-forwarded-proto"}, new String[] {"https"}), "https://127.0.0.1:8888"},

    });
  }

  public UriFilterProxyHeaderParserTest(
      String fProxyPort,
      String fProxyBindTo,
      String uriScheme,
      String uriHost,
      MultiValueTable<String, String> headers,
      String resultUriPrefix
  ) {
    this.fProxyPort = fProxyPort;
    this.fProxyBindTo = fProxyBindTo;
    this.uriScheme = uriScheme;
    this.uriHost = uriHost;
    this.headers = headers;
    this.resultUriPrefix = resultUriPrefix;
  }


  @Test
  public void testUriPrefixMatchesExpected() {
    String schemeHostAndPort = new UriFilterProxyHeaderParser(
        fProxyPort,
        fProxyBindTo,
        uriScheme,
        uriHost,
        headers)
        .getSchemeHostAndPort();
    assertTrue(
        String.format("schemeHostAndPort %s does not match expected %s",
            schemeHostAndPort, resultUriPrefix),
        schemeHostAndPort.equals(resultUriPrefix));

  }

}
