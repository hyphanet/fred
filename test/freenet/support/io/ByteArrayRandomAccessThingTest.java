package freenet.support.io;

public class ByteArrayRandomAccessThingTest extends BaseRandomAccessThingTest {

    private static final int[] TEST_LIST = new int[] { 0, 1, 32, 64, 32768, 1024*1024, 1024*1024+1 };
    
    public ByteArrayRandomAccessThingTest() {
        super(TEST_LIST);
    }

    @Override
    protected RandomAccessThing construct(long size) {
        assert(size < Integer.MAX_VALUE);
        return new ByteArrayRandomAccessThing(new byte[(int)size]);
    }

}
