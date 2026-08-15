/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.microemu.cldc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;

import org.junit.Test;

public class NetworkAddressUtilTest {
	@Test
	public void formatsIpv6UsingGcfBrackets() throws Exception {
		assertEquals("[2001:db8:0:0:0:0:0:1]",
				NetworkAddressUtil.format(InetAddress.getByName("2001:db8::1")));
	}

	@Test
	public void prefersSiteLocalIpv4ForWildcardServerDiscovery() throws Exception {
		InetAddress siteLocalIpv4 = InetAddress.getByName("192.168.10.20");
		InetAddress globalIpv6 = InetAddress.getByName("2001:db8::1");

		assertTrue(NetworkAddressUtil.score(siteLocalIpv4) < NetworkAddressUtil.score(globalIpv6));
	}

	@Test
	public void preservesExplicitServerBindAddress() throws Exception {
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			assertEquals(
					NetworkAddressUtil.format(InetAddress.getLoopbackAddress()),
					NetworkAddressUtil.getServerLocalAddress(server));
		}
	}
}
