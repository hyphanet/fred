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
        // {"", "", "", "", MultiValueTable.from(new String[] {}, new String[] {}), "http://"},
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
