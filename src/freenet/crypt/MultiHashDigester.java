package freenet.crypt;

import java.security.MessageDigest;
import java.util.Arrays;

final class MultiHashDigester {

    private final Digester[] digesters;

    private MultiHashDigester(Digester[] digesters) {
        this.digesters = digesters;
    }

    void update(byte[] input, int offset, int len) {
        for (Digester digester : digesters) {
            digester.update(input, offset, len);
        }
    }

    HashResult[] getResults() {
        return Arrays.stream(digesters)
                .map(Digester::getResult)
                .toArray(HashResult[]::new);
    }

    /**
     * Create a digester for the hash types indicated by the bitmask.
     *
     * @see HashType#bitmask
     */
    static MultiHashDigester fromBitmask(long bitmask) {
        Digester[] digesters = Arrays.stream(HashType.values())
                .filter(hashType -> (bitmask & hashType.bitmask) == hashType.bitmask)
                .map(Digester::new)
                .toArray(Digester[]::new);

        return new MultiHashDigester(digesters);
    }

    private static class Digester {
        private final HashType hashType;
        private final MessageDigest digest;

        Digester(HashType hashType) {
            this.hashType = hashType;
            digest = hashType.get();
        }

        HashResult getResult() {
            return new HashResult(hashType, digest.digest());
        }

        void update(byte[] input, int offset, int len) {
            digest.update(input, offset, len);
        }
    }
}
