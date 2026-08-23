/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.shell.timing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PresentationMailboxTest {
	@Test
	public void coalescesProducerPublishesWithoutDroppingTheFollowUpRequest() {
		PresentationMailbox mailbox = new PresentationMailbox();
		long generation = mailbox.begin();
		long first = mailbox.publish();
		assertTrue(mailbox.trySchedule(generation));
		long second = mailbox.publish();

		assertTrue(mailbox.complete(generation, first));
		assertFalse(mailbox.trySchedule(generation));
		assertFalse(mailbox.complete(generation, second));
	}

	@Test
	public void repeatedRenderOfSameSequenceIsNotConsideredAFrameRequest() {
		PresentationMailbox mailbox = new PresentationMailbox();
		long generation = mailbox.begin();
		long sequence = mailbox.publish();
		assertTrue(mailbox.trySchedule(generation));
		assertFalse(mailbox.complete(generation, sequence));
		assertFalse(mailbox.trySchedule(generation));
	}

	@Test
	public void staleGenerationCannotCompleteARequestAfterLifecycleRestart() {
		PresentationMailbox mailbox = new PresentationMailbox();
		long oldGeneration = mailbox.begin();
		mailbox.publish();
		assertTrue(mailbox.trySchedule(oldGeneration));
		mailbox.close();
		long newGeneration = mailbox.begin();

		assertFalse(mailbox.complete(oldGeneration, 1L));
		assertEquals(1L, mailbox.publish());
		assertTrue(mailbox.trySchedule(newGeneration));
	}

	@Test
	public void closedMailboxRejectsPublicationAndScheduling() {
		PresentationMailbox mailbox = new PresentationMailbox();
		long generation = mailbox.begin();
		mailbox.close();

		assertEquals(0L, mailbox.publish());
		assertFalse(mailbox.trySchedule(generation));
	}
}
