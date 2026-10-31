/* Licensed under the Apache License, Version 2.0. */
package io.github.h3nb.jlmodplus.memory

internal open class TestMemoryEditorActions : MemoryEditorActions {
    override fun close() = Unit
    override fun refreshCapabilities() = Unit
    override fun startSearch(value: String, secondValue: String, type: Int, predicate: Int, unknown: Boolean) = Unit
    override fun nextScan(value: String, secondValue: String, predicate: Int, compare: Int, type: Int) = Unit
    override fun groupSearch(type: Int, values: Array<String>) = Unit
    override fun undo() = Unit
    override fun refresh() = Unit
    override fun setWatchTab(watch: Boolean) = Unit
    override fun toggleSelection(id: Long) = Unit
    override fun selectVisible() = Unit
    override fun invertVisible() = Unit
    override fun clearSelection() = Unit
    override fun editSelected(value: String, type: Int) = Unit
    override fun removeSelected(keep: Boolean) = Unit
    override fun watchSelected(add: Boolean) = Unit
    override fun labelWatch(id: Long, label: String) = Unit
    override fun freezeSelected(mode: Int, first: String, second: String) = Unit
    override fun clearFreezeSelected() = Unit
    override fun copySelected(locations: Boolean) = Unit
    override fun previousPage() = Unit
    override fun nextPage() = Unit
    override fun cancel() = Unit
}
