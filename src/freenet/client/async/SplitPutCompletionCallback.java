package freenet.client.async;

import freenet.client.Metadata;

public interface SplitPutCompletionCallback extends PutCompletionCallback {

	public void onGeneratedMetadata(Metadata meta);
	
}
