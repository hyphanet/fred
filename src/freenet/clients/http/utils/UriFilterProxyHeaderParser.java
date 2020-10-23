package freenet.clients.http.utils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import freenet.support.MultiValueTable;

public class UriFilterProxyHeaderParser {
    private final String fProxyPortConfig;
    private final String fProxyBindToConfig;
    private final String uriScheme;
    private final String uriHost;
    private final MultiValueTable<String, String> headers;

    public UriFilterProxyHeaderParser(
        String fProxyPort,
        String fProxyBindTo,
        String uriScheme,
        String uriHost,
        MultiValueTable<String, String> headers
        ) {

        this.fProxyPortConfig = fProxyPort;
        this.fProxyBindToConfig = fProxyBindTo;
        this.uriScheme = uriScheme;
        this.uriHost = uriHost;
        this.headers = headers;
    }

    public String getSchemeHostAndPort() {
        Set<String> safeProtocols = new HashSet<>(Arrays.asList("http", "https"));

        List<String> bindToHosts = Arrays.asList(fProxyBindToConfig.split(",")).stream()
            .map(host -> host.contains(":") ? "[" + host + "]" : host)
            .collect(Collectors.toList());
        String firstBindToHost = bindToHosts.get(0);
        // set default values
        if (firstBindToHost.isEmpty()) {
            firstBindToHost = "127.0.0.1";
        }
        String port = fProxyPortConfig.isEmpty()
            ? "8888"
            : fProxyPortConfig;
        // allow all bindToHosts
        Set<String> safeHosts = new HashSet<>(bindToHosts);
        // also allow bindTo hosts with the fProxyPortConfig added
        safeHosts.addAll(safeHosts.stream()
                         .map(host -> host + ":" + port)
                         .collect(Collectors.toList()));

        // check uri host and headers
        // TODO: parse the Forwarded header, too. Skipped here to reduce the scope.
        String protocol = headers.containsKey("x-forwarded-proto")
            ? headers.get("x-forwarded-proto")
            : uriScheme != null && !uriScheme.trim().isEmpty() ? uriScheme : "http";
        String host = headers.containsKey("x-forwarded-host")
            ? headers.get("x-forwarded-host")
            : uriHost != null && !uriHost.trim().isEmpty() ? uriHost : headers.get("host");
        // check allow list
        if (!safeProtocols.contains(protocol)) {
            protocol = "http";
        }
        if (!safeHosts.contains(host)) {
            host = firstBindToHost + ":" + port;
        }
        return protocol + "://" + host;
    }
}
