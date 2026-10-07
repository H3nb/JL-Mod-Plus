/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist

import org.junit.runner.RunWith
import org.junit.runners.Suite

// AGP's test engine uses commas between instrumentation arguments, truncating CSV class values.
@RunWith(Suite::class)
@Suite.SuiteClasses(
    LibraryViewportNavigationTest::class,
    LibraryIconRefreshTest::class,
    LibraryCollectionsNavigationTest::class,
    LibraryComposeTest::class,
)
class LibraryUiContractSuite
