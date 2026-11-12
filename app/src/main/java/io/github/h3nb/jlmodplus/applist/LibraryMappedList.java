/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

/** Indexed Library projection; presentation items are constructed only when requested. */
final class LibraryMappedList<S, T> extends AbstractList<T> implements RandomAccess {
    interface Mapper<S, T> {
        T map(S source);
    }

    private final List<S> source;
    private final Mapper<S, T> mapper;

    LibraryMappedList(List<S> source, Mapper<S, T> mapper) {
        this.source = source;
        this.mapper = mapper;
    }

    @Override
    public T get(int index) {
        return mapper.map(source.get(index));
    }

    @Override
    public int size() {
        return source.size();
    }
}
