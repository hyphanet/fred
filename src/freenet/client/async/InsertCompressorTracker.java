package freenet.client.async;

import java.util.LinkedHashSet;
import java.util.Set;

public class InsertCompressorTracker {
    
    private final Set<InsertCompressor> compressors = new LinkedHashSet<InsertCompressor>();

    public synchronized void add(InsertCompressor compressor) {
        compressors.add(compressor);
    }

    public synchronized void remove(InsertCompressor insertCompressor) {
        compressors.remove(insertCompressor);
    }

    public synchronized InsertCompressor[] list() {
        return compressors.toArray(new InsertCompressor[compressors.size()]);
    }

}
