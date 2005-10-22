package freenet.client;

import java.io.IOException;

/** Thrown when Metadata parse fails. */
public class MetadataParseException extends IOException {

	public MetadataParseException(String string) {
		super(string);
	}

}
