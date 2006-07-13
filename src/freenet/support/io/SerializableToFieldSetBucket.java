package freenet.support.io;

import freenet.support.Bucket;
import freenet.support.SimpleFieldSet;

public interface SerializableToFieldSetBucket extends Bucket {
	
	public SimpleFieldSet toFieldSet();

}
