/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.librarydb

import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDisplayProjectionTest {
    @Test fun queriesAndCollectionUpdatesReuseOrderWithoutNarrowingAuthoritativeIds() = runBlocking {
        val source = LibraryRepository.State.Ready(1, File("first"), listOf(
            row(1, "Zulu", favorite = true), row(2, "Alpha")))
        val repository = MutableStateFlow<LibraryRepository.State>(source)
        val query = MutableStateFlow("")
        val quickView = MutableStateFlow(LibraryQuickView.All)
        val results = Channel<LibraryViewModel.DisplayState>(Channel.UNLIMITED)
        val collector = launch {
            libraryDisplayStates(repository, query, MutableStateFlow(0), quickView,
                MutableStateFlow(Locale.US)).collect { results.send(it) }
        }
        try {
            val initial = results.ready()
            query.value = "zulu"
            val searched = results.ready()
            assertEquals(listOf(1L), searched.apps.map { it.id })
            assertSame(initial.availableAppIds, searched.availableAppIds)
            assertEquals(setOf(1L, 2L), searched.availableAppIds)
            assertEquals(initial.sourceRevision, searched.sourceRevision)

            query.value = ""
            assertSame(initial.apps, results.ready().apps)
            val collection = LibraryCollectionRow(9, "Collection", 0, 100, 1)
            repository.value = source.copy(apps = source.apps.toList(), collections = listOf(collection))
            val collectionUpdate = results.ready()
            assertSame(initial.apps, collectionUpdate.apps)
            assertSame(initial.availableAppIds, collectionUpdate.availableAppIds)
            assertEquals(listOf(collection), collectionUpdate.collections)
            assertTrue(collectionUpdate.sourceRevision > initial.sourceRevision)

            quickView.value = LibraryQuickView.Favorites
            val favorites = results.ready()
            assertEquals(listOf(1L), favorites.apps.map { it.id })
            assertSame(initial.availableAppIds, favorites.availableAppIds)
            assertEquals(collectionUpdate.sourceRevision, favorites.sourceRevision)
            quickView.value = LibraryQuickView.All
            assertSame(initial.apps, results.ready().apps)

            repository.value = source.copy(apps = source.apps.map {
                if (it.id == 1L) it.copy(title = "Aardvark", favorite = false) else it
            }, collections = listOf(collection))
            val renamed = results.ready()
            assertEquals(listOf(1L, 2L), renamed.apps.map { it.id })
            assertEquals("Aardvark", renamed.apps.first().title)
            assertNotSame(initial.apps, renamed.apps)
            quickView.value = LibraryQuickView.Favorites
            val noFavorites = results.ready()
            assertTrue(noFavorites.apps.isEmpty())
            assertEquals(setOf(1L, 2L), noFavorites.availableAppIds)
        } finally {
            collector.cancelAndJoin()
            results.close()
        }
    }

    @Test fun localeOrderAndGenerationChangesInvalidateTheSinglePreparedSnapshot() = runBlocking {
        val source = LibraryRepository.State.Ready(1, File("first"), listOf(
            row(1, "Alpha"), row(2, "Ångström"), row(3, "Zebra")))
        val repository = MutableStateFlow<LibraryRepository.State>(source)
        val locale = MutableStateFlow(Locale.US)
        val sort = MutableStateFlow(0)
        val results = Channel<LibraryViewModel.DisplayState>(Channel.UNLIMITED)
        val collector = launch {
            libraryDisplayStates(repository, MutableStateFlow(""), sort,
                MutableStateFlow(LibraryQuickView.All), locale).collect { results.send(it) }
        }
        try {
            val initial = results.ready()
            assertEquals(listOf(1L, 2L, 3L), initial.apps.map { it.id })
            locale.value = Locale.forLanguageTag("sv-SE")
            val swedish = results.ready()
            assertEquals(listOf(1L, 3L, 2L), swedish.apps.map { it.id })
            assertNotSame(initial.apps, swedish.apps)
            assertEquals(initial.sourceRevision, swedish.sourceRevision)
            sort.value = Int.MIN_VALUE
            val descending = results.ready()
            assertEquals(listOf(2L, 3L, 1L), descending.apps.map { it.id })

            repository.value = source.copy(generation = 2, emulatorDir = File("second"))
            val second = results.ready()
            assertEquals(File("second"), second.emulatorDir)
            assertEquals(2L, second.generation)
            assertNotSame(descending.apps, second.apps)
            repository.value = source.copy(generation = 3)
            val returned = results.ready()
            assertEquals(File("first"), returned.emulatorDir)
            assertEquals(3L, returned.generation)
            assertNotSame(second.apps, returned.apps)
            assertNotSame(descending.apps, returned.apps)
        } finally {
            collector.cancelAndJoin()
            results.close()
        }
    }

    @Test fun openingCancelsQueuedOldProjectionAndOnlyLatestSourceQueryCanPublish() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.asCoroutineDispatcher()
        val queued = CompletableDeferred<Job>()
        val queuedWorker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                worker.dispatch(context, block)
                queued.complete(requireNotNull(context[Job]))
            }
        }
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute { blocked.countDown(); release.await() }
        assertTrue(blocked.await(5, TimeUnit.SECONDS))
        val first = File("first")
        val second = File("second")
        val repository = MutableStateFlow<LibraryRepository.State>(
            LibraryRepository.State.Ready(1, first, listOf(row(1, "Old"))))
        val query = MutableStateFlow("old")
        val newQuerySeen = CompletableDeferred<Unit>()
        val results = Channel<LibraryViewModel.DisplayState>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            libraryDisplayStates(repository, query.onEach { if (it == "new") newQuerySeen.complete(Unit) },
                MutableStateFlow(0), MutableStateFlow(LibraryQuickView.All),
                MutableStateFlow(Locale.US), queuedWorker).collect { results.send(it) }
        }
        try {
            val oldWork = withTimeout(5_000) { queued.await() }
            repository.value = LibraryRepository.State.Opening(2, second)
            withTimeout(5_000) { while (!oldWork.isCancelled) yield() }
            query.value = "new"
            withTimeout(5_000) { newQuerySeen.await() }
            repository.value = LibraryRepository.State.Ready(2, second,
                listOf(row(1, "New"), row(2, "Other")))
            release.countDown()
            val latest = results.ready()
            assertEquals(2L, latest.generation)
            assertEquals(second, latest.emulatorDir)
            assertEquals("new", latest.filter)
            assertEquals(listOf("New"), latest.apps.map { it.title })
            assertEquals(setOf(1L, 2L), latest.availableAppIds)
        } finally {
            release.countDown()
            collector.cancelAndJoin()
            worker.close()
            results.close()
        }
    }

    @Test fun recentOrderReusesUnusedSortButInvalidatesWhenPlayStatisticsChange() = runBlocking {
        val source = LibraryRepository.State.Ready(1, File("first"), listOf(
            row(1, "Old").copy(lastPlayedAt = 100),
            row(2, "Newest").copy(lastPlayedAt = 300), row(3, "Never")))
        val repository = MutableStateFlow<LibraryRepository.State>(source)
        val sort = MutableStateFlow(0)
        val results = Channel<LibraryViewModel.DisplayState>(Channel.UNLIMITED)
        val collector = launch {
            libraryDisplayStates(repository, MutableStateFlow(""), sort,
                MutableStateFlow(LibraryQuickView.RecentlyPlayed), MutableStateFlow(Locale.US))
                .collect { results.send(it) }
        }
        try {
            val initial = results.ready()
            assertEquals(listOf(2L, 1L), initial.apps.map { it.id })
            assertEquals(setOf(1L, 2L, 3L), initial.availableAppIds)
            sort.value = LibraryListProjection.SORT_VENDOR or Int.MIN_VALUE
            val changedSort = results.ready()
            assertEquals(sort.value, changedSort.sortVariant)
            assertSame(initial.apps, changedSort.apps)
            repository.value = source.copy(apps = source.apps.map {
                if (it.id == 3L) it.copy(lastPlayedAt = 500, playCount = 1) else it
            })
            val played = results.ready()
            assertEquals(listOf(3L, 2L, 1L), played.apps.map { it.id })
            assertEquals(500L, played.apps.first().lastPlayedAt)
            assertNotSame(initial.apps, played.apps)
            assertTrue(played.sourceRevision > initial.sourceRevision)
        } finally {
            collector.cancelAndJoin()
            results.close()
        }
    }

    private suspend fun Channel<LibraryViewModel.DisplayState>.ready() = withTimeout(5_000) {
        var state = receive()
        while (state !is LibraryViewModel.DisplayState.Ready) state = receive()
        state
    }

    private fun row(id: Long, title: String, favorite: Boolean = false) = LibraryAppRow(
        id, "app-$id", title, "Vendor", "1.0", title, "Vendor", "1.0", "",
        favorite = favorite, addedAt = null, lastPlayedAt = null, iconRevision = 0)
}
