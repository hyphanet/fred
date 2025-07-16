package freenet.crypt;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

final class MultiHashDigester {

    private final Collection<Digester> digesters;

    private MultiHashDigester(Collection<Digester> digesters) {
        this.digesters = digesters;
    }

    void update(byte[] input, int offset, int len) {
        digesters.forEach(digester -> digester.update(input, offset, len));
    }

    List<HashResult> getResults() {
        return digesters.stream()
                .map(Digester::getResult)
                .collect(Collectors.toList());
    }

    /**
     * Create a digester for the hash types indicated by the bitmask.
     *
     * @see HashType#bitmask
     */
    static MultiHashDigester fromBitmask(long bitmask) {
        List<Digester> digesters = Arrays.stream(HashType.values())
                .filter(hashType -> (bitmask & hashType.bitmask) == hashType.bitmask)
                .map(Digester::new)
                .collect(Collectors.toList());

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
