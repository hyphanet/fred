package freenet.client;

import java.io.IOException;

/** Thrown when Metadata parse fails. */
public class MetadataParseException extends IOException {

	private static final long serialVersionUID = 4910650977022715220L;

	public MetadataParseException(String string) {
		super(string);
	}

}
