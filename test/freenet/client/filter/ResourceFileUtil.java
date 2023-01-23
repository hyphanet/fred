package freenet.client.filter;

import freenet.support.io.ArrayBucket;
import freenet.support.io.BucketTools;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

class ResourceFileUtil {

    @FunctionalInterface
    interface ResourceFileStreamConsumer {
        void processDataInputStream(DataInputStream dataInputStream) throws IOException;
    }

    static void testResourceFile(String fileName, ResourceFileStreamConsumer inputStreamConsumer) throws IOException {
        try (
            InputStream resourceAsStream = ResourceFileUtil.class.getResourceAsStream(fileName);
        ) {
            if (resourceAsStream == null) {
                throw new RuntimeException("File should exist: " + fileName);
            }
            try(DataInputStream input = new DataInputStream(resourceAsStream)) {
                inputStreamConsumer.processDataInputStream(input);
            }
        }
    }

    static ArrayBucket resourceToBucket(String filename) throws IOException {
        ArrayBucket ab;
        try (InputStream is = ResourceFileUtil.class.getResourceAsStream(filename)) {
            if (is == null) {
                throw new java.io.FileNotFoundException(filename);
            }
            ab = new ArrayBucket();
            BucketTools.copyFrom(ab, is, Long.MAX_VALUE);
        }
        return ab;
    }
}
