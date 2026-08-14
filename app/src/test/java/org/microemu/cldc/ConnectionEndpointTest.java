/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.microemu.cldc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ConnectionEndpointTest {
	@Test
	public void parsesLegacySocketEndpointsWithoutChangingTransport() {
		ConnectionEndpoint endpoint = ConnectionEndpoint.parse(
				"socket://game.example:19126;interface=wifi", "socket");

		assertEquals("game.example", endpoint.getHost());
		assertEquals(19126, endpoint.getPort());
	}

	@Test
	public void parsesBracketedIpv6() {
		ConnectionEndpoint endpoint = ConnectionEndpoint.parse("socket://[::1]:14444", "socket");

		assertEquals("::1", endpoint.getHost());
		assertEquals(14444, endpoint.getPort());
	}

	@Test
	public void preservesServerSocketForms() {
		ConnectionEndpoint dynamic = ConnectionEndpoint.parse("socket://", "socket");
		ConnectionEndpoint fixed = ConnectionEndpoint.parse("socket://:4321", "socket");

		assertEquals("", dynamic.getHost());
		assertEquals(-1, dynamic.getPort());
		assertEquals("", fixed.getHost());
		assertEquals(4321, fixed.getPort());
	}
}
