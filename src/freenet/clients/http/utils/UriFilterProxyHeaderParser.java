package freenet.clients.http.utils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import freenet.config.Option;
import freenet.support.MultiValueTable;

public class UriFilterProxyHeaderParser {
    private UriFilterProxyHeaderParser() {}

    public static SchemeAndHostWithPort parse(
        Option<?> fProxyPortConfig,
        Option<?> fProxyBindToConfig,
        String uriScheme,
        String uriHost,
        MultiValueTable<String, String> headers
    ) {
        Set<String> safeProtocols = new HashSet<>(Arrays.asList("http", "https"));

        List<String> bindToHosts = Arrays.stream(fProxyBindToConfig.getValueString().split(","))
            .map(host -> host.contains(":") ? "[" + host + "]" : host)
            .collect(Collectors.toList());
        String firstBindToHost = bindToHosts.get(0);
        // set default values
        if (firstBindToHost.isEmpty()) {
            firstBindToHost = "127.0.0.1";
        }
        String port = fProxyPortConfig.getValueString().isEmpty()
            ? "8888"
            : fProxyPortConfig.getValueString();
        // allow all bindToHosts
        Set<String> safeHosts = new HashSet<>(bindToHosts);
        // also allow bindTo hosts with the fProxyPortConfig added
        safeHosts.addAll(safeHosts.stream()
                         .map(host -> host + ":" + port)
                         .collect(Collectors.toList()));
        // check uri host and headers
        Map<String, String> forwarded = parseForwardedHeader(headers.get("forwarded"));
        String protocol = forwarded.getOrDefault("proto", headers.containsKey("x-forwarded-proto")
            ? headers.get("x-forwarded-proto")
            : uriScheme != null && !uriScheme.trim().isEmpty() ? uriScheme : "http");
        String host = forwarded.getOrDefault("host", headers.containsKey("x-forwarded-host")
            ? headers.get("x-forwarded-host")
            : uriHost != null && !uriHost.trim().isEmpty() ? uriHost : headers.get("host"));
        // check allow list
        if (!safeProtocols.contains(protocol)) {
            protocol = "http";
        }
        if (!safeHosts.contains(host)) {
            host = firstBindToHost + ":" + port;
        }
        return new SchemeAndHostWithPort(protocol, host);
    }

    public static class SchemeAndHostWithPort {
        private final String scheme;
        private final String host;

        SchemeAndHostWithPort(
            String scheme,
            String host
        ) {
            this.scheme = scheme;
            this.host = host;
        }

        public String toString() {
            return scheme + "://" + host;
        }
    }

    static Map<String, String> parseForwardedHeader(String forwarded) {
        if (forwarded == null || forwarded.trim().isEmpty()) {
            return new HashMap<>();
        }
        Map<String, String> headerParams = new HashMap<>();

        // if a multi-value header is given, only use the first value.
        int indexOfComma = forwarded.indexOf(',');
        if (indexOfComma != -1) {
            forwarded = forwarded.substring(0, indexOfComma);
        }
        boolean hasAtLeastOneKey = forwarded.indexOf('=') != -1;
        boolean hasMultipleKeys = forwarded.indexOf(';') != -1;
        String[] fields;
        if (hasMultipleKeys) {
            fields = forwarded.split(";");
        } else if (hasAtLeastOneKey) {
            fields = new String[]{ forwarded };
        } else {
            return headerParams;
        }
        for (String field : fields) {
            if (field.indexOf('=') != 1) {
                String[] keyAndValue = field.split("=");
                headerParams.put(keyAndValue[0].toLowerCase(), keyAndValue[1]);
            }
        }
        return headerParams;
    }
}
