package freenet.node;

/**
 * Fluent-style builder for {@link RequestClient} implementations. The default {@code
 * RequestClient} built by this builder is not persistent and not real-time.
 *
 * @author <a href="mailto:bombe@freenetproject.org">David ‘Bombe’ Roden</a>
 */
public class RequestClientBuilder {

    private boolean persistent;
    private boolean realTime;

    public RequestClientBuilder persistent() {
        persistent = true;
        return this;
    }

    public RequestClientBuilder persistent(boolean persistent) {
        this.persistent = persistent;
        return this;
    }

    public RequestClientBuilder realTime() {
        realTime = true;
        return this;
    }

    public RequestClientBuilder realTime(boolean realTime) {
        this.realTime = realTime;
        return this;
    }

    /**
     * Builds a {@link RequestClient}. Once this method has been called the returned {@code
     * RequestClient} is not connected to this builder anymore; the resulting {@code RequestClient}
     * will never change. With this it’s possible to reuse this builder instances for creating more
     * {@code RequestClient}s.
     *
     * @return A new {@code RequestClient} with the given settings
     */
    public RequestClient build() {
        return new RequestClient() {
            private final boolean persistent = RequestClientBuilder.this.persistent;
            private final boolean realTime = RequestClientBuilder.this.realTime;

            @Override
            public boolean persistent() {
                return persistent;
            }

            @Override
            public boolean realTimeFlag() {
                return realTime;
            }
        };
    }

}
