/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.agent;

import java.net.URI;
import java.net.URISyntaxException;

import io.cryostat.agent.ConfigModule.URIRange;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// FIXME we actually perform a DNS lookup to validate the URI. Unit tests should not do actual DNS
// resolution but instead be able to validate what the host symbolically represents.
public class URIRangeTest {

    @ParameterizedTest
    @CsvSource({
        "127.0.0.1, true",
        "localhost, true",
        "svc.localhost, true",
        "169.254.0.0, false",
        "169.254.10.5, false",
        "168.254.1.1, false",
        "10.0.0.10, false",
        "10.0.10.10, false",
        "10.10.10.10, false",
        "11.10.10.10, false",
        "172.16.0.0, false",
        "172.16.10.5, false",
        "172.17.10.5, false",
        "172.100.10.5, false",
        "192.168.1.1, false",
        "192.168.2.1, false",
        "192.169.2.1, false",
        "svc.local, false",
        "example.svc.cluster.local, false",
        "example.com, false"
    })
    public void testLoopbackRange(String s, String v) throws URISyntaxException {
        test(URIRange.LOOPBACK, s, v);
    }

    @ParameterizedTest
    @CsvSource({
        "127.0.0.1, true",
        "localhost, true",
        "svc.localhost, true",
        "169.254.0.0, true",
        "169.254.10.5, true",
        "168.254.1.1, false",
        "10.0.0.10, false",
        "10.0.10.10, false",
        "10.10.10.10, false",
        "11.10.10.10, false",
        "172.16.0.0, false",
        "172.16.10.5, false",
        "172.17.10.5, false",
        "172.100.10.5, false",
        "192.168.1.1, false",
        "192.168.2.1, false",
        "192.169.2.1, false",
        "svc.local, false",
        "example.svc.cluster.local, false",
        "example.com, false"
    })
    public void testLinkLocalRange(String s, String v) throws URISyntaxException {
        test(URIRange.LINK_LOCAL, s, v);
    }

    @ParameterizedTest
    @CsvSource({
        "127.0.0.1, true",
        "localhost, true",
        "svc.localhost, true",
        "169.254.0.0, true",
        "169.254.10.5, true",
        "168.254.1.1, false",
        "10.0.0.10, true",
        "10.0.10.10, true",
        "10.10.10.10, true",
        "11.10.10.10, false",
        "172.16.0.0, true",
        "172.16.10.5, true",
        "172.17.10.5, true",
        "172.100.10.5, false",
        "192.168.1.1, true",
        "192.168.2.1, true",
        "192.170.2.1, false",
        "svc.local, false",
        "example.svc.cluster.local, false",
        "example.com, false"
    })
    public void testSiteLocalRange(String s, String v) throws URISyntaxException {
        test(URIRange.SITE_LOCAL, s, v);
    }

    @ParameterizedTest
    @CsvSource({
        "127.0.0.1, true",
        "localhost, true",
        "svc.localhost, true",
        "169.254.0.0, true",
        "169.254.10.5, true",
        "168.254.1.1, false",
        "10.0.0.10, true",
        "10.0.10.10, true",
        "10.10.10.10, true",
        "11.10.10.10, false",
        "172.16.0.0, true",
        "172.16.10.5, true",
        "172.17.10.5, true",
        "172.100.10.5, false",
        "192.168.1.1, true",
        "192.168.2.1, true",
        "192.169.2.1, false",
        "svc.local, true",
        "example.svc.cluster.local, true",
        "example.com, false"
    })
    public void testDnsLocalRange(String s, String v) throws URISyntaxException {
        test(URIRange.DNS_LOCAL, s, v);
    }

    @ParameterizedTest
    @CsvSource({
        "127.0.0.1, true",
        "localhost, true",
        "svc.localhost, true",
        "169.254.0.0, true",
        "169.254.10.5, true",
        "168.254.1.1, true",
        "10.0.0.10, true",
        "10.0.10.10, true",
        "10.10.10.10, true",
        "11.10.10.10, true",
        "172.16.0.0, true",
        "172.16.10.5, true",
        "172.17.10.5, true",
        "172.100.10.5, true",
        "192.168.1.1, true",
        "192.168.2.1, true",
        "192.169.2.1, true",
        "svc.local, true",
        "example.svc.cluster.local, true",
        "example.com, true"
    })
    public void testPublicRange(String s, String v) throws URISyntaxException {
        test(URIRange.PUBLIC, s, v);
    }

    private void test(URIRange range, String s, String v) throws URISyntaxException {
        boolean expected = Boolean.parseBoolean(v);
        URI u = new URI(String.format("http://%s:1234", s));
        MatcherAssert.assertThat(s, range.validate(u), Matchers.is(expected));
    }
}
