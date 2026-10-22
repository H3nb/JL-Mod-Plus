/**
 * MicroEmulator
 * Copyright (C) 2006-2007 Bartek Teodorczyk <barteo@barteo.net>
 * Copyright (C) 2006-2007 Vlad Skarzhevskyy
 * <p>
 * It is licensed under the following two licenses as alternatives:
 * 1. GNU Lesser General Public License (the "LGPL") version 2.1 or any newer version
 * 2. Apache License (the "AL") Version 2.0
 * <p>
 * You may not use this file except in compliance with at least one of
 * the above two licenses.
 * <p>
 * You may obtain a copy of the LGPL at
 * http://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt
 * <p>
 * You may obtain a copy of the AL at
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the LGPL or the AL for the specific language governing permissions and
 * limitations.
 *
 * @version $Id$
 */
package org.microemu.microedition.io;

import org.microemu.microedition.Implementation;

import java.io.IOException;

import javax.microedition.io.ConnectionNotFoundException;

/**
 *
 * Default implementation: no push connection or alarm delivery backend is installed.
 *
 * @author vlads
 */

public class PushRegistryImpl implements PushRegistryDelegate, Implementation {

	public String getFilter(String connection) {
		return null;
	}

	public String getMIDlet(String connection) {
		return null;
	}

	public String[] listConnections(boolean available) {
		return new String[0];
	}

	public long registerAlarm(String midlet, long time) throws ClassNotFoundException, ConnectionNotFoundException {
		if (midlet == null) throw new ClassNotFoundException("MIDlet is null");
		throw new ConnectionNotFoundException("Push alarms are not supported");
	}

	public void registerConnection(String connection, String midlet, String filter) throws ClassNotFoundException,
			IOException {
		if (connection == null || filter == null) {
			throw new IllegalArgumentException("Push connection and filter must not be null");
		}
		if (midlet == null) throw new ClassNotFoundException("MIDlet is null");
		if (connection.isEmpty() || filter.isEmpty()) {
			throw new IllegalArgumentException("Invalid push connection or filter");
		}
		// Do not claim registration success without an AMS listener capable of delivering it.
		throw new ConnectionNotFoundException("Push connections are not supported");
	}

	public boolean unregisterConnection(String connection) {
		return false;
	}

}
