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

package io.github.h3nb.jlmodplus.applist;

import androidx.annotation.Nullable;

/**
 * Rejects stale asynchronous Collection-member results without becoming a navigation owner.
 *
 * <p>The Compose navigation state decides which Collection is selected. This gate only records the
 * newest request issued for that route so a late callback cannot replace a newer projection.</p>
 */
final class CollectionMembersRequestGate {
    static final class Request {
        final long sequence;
        final long collectionId;
        final long generation;

        Request(long sequence, long collectionId, long generation) {
            this.sequence = sequence;
            this.collectionId = collectionId;
            this.generation = generation;
        }
    }

    private long nextSequence;
    @Nullable
    private Request current;

    Request begin(long collectionId, long generation) {
        Request request = new Request(++nextSequence, collectionId, generation);
        current = request;
        return request;
    }

    void invalidate() {
        nextSequence++;
        current = null;
    }

    boolean accepts(Request request, long activeGeneration) {
        Request latest = current;
        return latest != null
                && latest.sequence == request.sequence
                && latest.collectionId == request.collectionId
                && latest.generation == request.generation
                && request.generation == activeGeneration;
    }

    boolean targets(long collectionId) {
        Request latest = current;
        return latest != null && latest.collectionId == collectionId;
    }

    @Nullable
    Long targetCollectionId() {
        Request latest = current;
        return latest == null ? null : latest.collectionId;
    }
}
