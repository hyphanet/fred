package freenet.io.comm;

import freenet.io.AddressTracker;

import java.net.InetAddress;

public interface UdpSocketHandler extends PacketSocketHandler {

    // CompuServe use 1400 MTU; AOL claim 1450; DFN@home use 1448.
    // http://info.aol.co.uk/broadband/faqHomeNetworking.adp
    // http://www.compuserve.de/cso/hilfe/linux/hilfekategorien/installation/contentview.jsp?conid=385700
    // http://www.studenten-ins-netz.net/inhalt/service_faq.html
    // officially GRE is 1476 and PPPoE is 1492.
    // unofficially, PPPoE is often 1472 (seen in the wild). Also PPPoATM is sometimes 1472.
    int MAX_ALLOWED_MTU = 1280;
    int UDPv4_HEADERS_LENGTH = 28;
    int UDPv6_HEADERS_LENGTH = 48;

    int MIN_IPv4_MTU = 576;
    int MIN_IPv6_MTU = 1280;

    // conservative estimation when AF is not known
    int UDP_HEADERS_LENGTH = UDPv6_HEADERS_LENGTH;
    // conservative estimation when AF is not known
    int MIN_MTU = MIN_IPv4_MTU;

    void setLowLevelFilter(IncomingPacketFilter f);

    InetAddress getBindTo();

    String getTitle();

    void run();

    void sendPacket(byte[] blockToSend, Peer destination, boolean allowLocalAddresses) throws Peer.LocalAddressException;

    int getMaxPacketSize();

    int calculateMaxPacketSize();

    int getPacketSendThreshold();

    void start();

    void close();

    int getDropProbability();

    void setDropProbability(int dropProbability);

    int getPortNumber();

    @Override
    String toString();

    int getHeadersLength();

    int getHeadersLength(Peer peer);

    AddressTracker getAddressTracker();

    void rescanPortForward();

    AddressTracker.Status getDetectedConnectivityStatus();

    int getPriority();

    long getStartTime();
}
