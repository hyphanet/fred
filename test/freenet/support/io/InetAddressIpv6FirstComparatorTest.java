package freenet.support.io;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class InetAddressIpv6FirstComparatorTest {

    @Test
    public void localBroadcastAddressIsAfterLoopback() throws UnknownHostException {
        InetAddress broadcastAddress = Inet6Address.getByName("[::]");
        InetAddress localhost = Inet6Address.getByName("[::1]");
        List<InetAddress> addresses = Arrays.asList(broadcastAddress, localhost);
        addresses.sort(InetAddressIpv6FirstComparator.COMPARATOR);
        assertThat(addresses, contains(localhost, broadcastAddress));
    }

    @Test
    public void localhostAndLinkLocalAreAfterGlobal() throws UnknownHostException {
        InetAddress localhost = Inet6Address.getByName("[::1]");
        InetAddress linkLocal = Inet6Address.getByName("[fe80::]");
        InetAddress globalAddress = Inet6Address.getByName("[1234::5]");
        List<InetAddress> addresses = Arrays.asList(localhost, linkLocal, globalAddress);
        addresses.sort(InetAddressIpv6FirstComparator.COMPARATOR);
        assertThat(addresses, contains(globalAddress, linkLocal, localhost));
    }

    @Test
    public void iPv4AreAfterIpv6() throws UnknownHostException {
        InetAddress ipv6 = Inet6Address.getByName("[1234::5]");
        InetAddress ipv4 = InetAddress.getByName("1.2.3.4");
        List<InetAddress> addresses = Arrays.asList(ipv4, null, ipv6);
        addresses.sort(InetAddressIpv6FirstComparator.COMPARATOR);
        assertThat(addresses, contains(ipv6, ipv4, null));
    }

    @Test
    public void nullIsLast() throws UnknownHostException {
        InetAddress ipv6 = Inet6Address.getByName("[1234::5]");
        List<InetAddress> addresses = Arrays.asList(null, ipv6);
        addresses.sort(InetAddressIpv6FirstComparator.COMPARATOR);
        assertThat(addresses, contains(ipv6, null));
    }

    @Test
    public void linkLocalIpv6IsAfter() throws UnknownHostException {
        InetAddress linkLocal = Inet6Address.getByName("[fe80::]");
        InetAddress ipv4 = InetAddress.getByName("1.2.3.4");
        List<InetAddress> addresses = Arrays.asList(linkLocal, ipv4);
        addresses.sort(InetAddressIpv6FirstComparator.COMPARATOR);
        assertThat(addresses, contains(ipv4, linkLocal));
    }

}
