package freenet.crypt;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class MultiHashDigesterTest {

    @Test
    public void createFromBitmask() {
        for (HashType hashType : HashType.values()) {
            MultiHashDigester digester = MultiHashDigester.fromBitmask(hashType.bitmask);
            HashResult[] results = digester.getResults();

            assertEquals(1, results.length);
            assertEquals(hashType, results[0].type);
        }
    }

    @Test
    public void noHash() {
        MultiHashDigester digester = MultiHashDigester.fromBitmask(0);
        HashResult[] results = digester.getResults();

        assertEquals(0, results.length);
    }

    @Test
    public void multiHash() {
        MultiHashDigester digester = MultiHashDigester.fromBitmask(
                HashType.SHA1.bitmask | HashType.MD5.bitmask | HashType.SHA256.bitmask
        );
        HashResult[] results = digester.getResults();

        assertEquals(3, results.length);
        assertEquals(HashType.SHA1, results[0].type);
        assertEquals(HashType.MD5, results[1].type);
        assertEquals(HashType.SHA256, results[2].type);
    }

    @Test
    public void digestEmpty() {
        MultiHashDigester digester = MultiHashDigester.fromBitmask(
                HashType.SHA1.bitmask | HashType.MD5.bitmask | HashType.SHA256.bitmask
        );

        HashResult[] results = digester.getResults();
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", results[0].hashAsHex());
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", results[1].hashAsHex());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", results[2].hashAsHex());
    }

    @Test
    public void updateAndDigest() {
        MultiHashDigester digester = MultiHashDigester.fromBitmask(
                HashType.SHA1.bitmask | HashType.MD5.bitmask | HashType.SHA256.bitmask
        );
        digester.update("$".getBytes(StandardCharsets.UTF_8), 0, 1);

        HashResult[] results = digester.getResults();
        assertEquals("3cdf2936da2fc556bfa533ab1eb59ce710ac80e5", results[0].hashAsHex());
        assertEquals("c3e97dd6e97fb5125688c97f36720cbe", results[1].hashAsHex());
        assertEquals("09fc96082d34c2dfc1295d92073b5ea1dc8ef8da95f14dfded011ffb96d3e54b", results[2].hashAsHex());
    }
}
