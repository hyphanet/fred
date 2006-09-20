package freenet.support.io;

import freenet.support.SimpleFieldSet;

public interface SerializableToFieldSetBucket extends Bucket {
	
	public SimpleFieldSet toFieldSet();

}
