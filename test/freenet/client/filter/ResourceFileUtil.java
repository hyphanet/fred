package freenet.client.filter;

import freenet.support.io.ArrayBucket;
import freenet.support.io.BucketTools;

import java.io.DataInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

class ResourceFileUtil {

    static DataInputStream resourceToDataInputStream(String fileName) throws IOException {
        InputStream resourceAsStream = ResourceFileUtil.class.getResourceAsStream(fileName);
        if (resourceAsStream == null) {
            throw new FileNotFoundException(fileName);
        }
        return new DataInputStream(resourceAsStream);
    }

    static OggPage resourceToOggPage(String fileName) throws IOException {
        try (DataInputStream input = resourceToDataInputStream(fileName)) {
            return OggPage.readPage(input);
        }
    }

    static ArrayBucket resourceToBucket(String fileName) throws IOException {
        ArrayBucket ab;
        try (InputStream is = ResourceFileUtil.class.getResourceAsStream(fileName)) {
            if (is == null) {
                throw new FileNotFoundException(fileName);
            }
            ab = new ArrayBucket();
            BucketTools.copyFrom(ab, is, Long.MAX_VALUE);
        }
        return ab;
    }
}
