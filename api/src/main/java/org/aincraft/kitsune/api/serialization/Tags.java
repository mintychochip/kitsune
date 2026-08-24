package org.aincraft.kitsune.api.serialization;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Mutable tag collection with fluent API for functional transformation.
 * Extends Collection for compatibility, provides chaining methods.
 */
public final class Tags implements Collection<String> {

    private final Collection<String> delegate;

    public Tags() {
        this.delegate = new HashSet<>();
    }

    public Tags(int initialCapacity) {
        this.delegate = new HashSet<>(initialCapacity);
    }

    // ===== Fluent API methods (return this for chaining) =====

    /**
     * Adds a single tag, returns this for chaining.
     * Use this instead of Collection.add() for fluent style.
     */
    public Tags push(String tag) {
        delegate.add(tag);
        return this;
    }

    /**
     * Adds multiple tags, returns this for chaining.
     */
    public Tags push(String... tags) {
        Collections.addAll(delegate, tags);
        return this;
    }

    /**
     * Adds all from set, returns this for chaining.
     */
    public Tags pushAll(Set<String> tags) {
        delegate.addAll(tags);
        return this;
    }

    /**
     * Conditionally adds tags, returns this for chaining.
     */
    public Tags pushIf(boolean condition, String... tags) {
        if (condition) {
            Collections.addAll(delegate, tags);
        }
        return this;
    }

    /**
     * Executes action if condition is true, returns this for chaining.
     */
    public Tags when(boolean condition, Consumer<Tags> action) {
        if (condition) {
            action.accept(this);
        }
        return this;
    }

    // ===== Collection delegation =====

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return delegate.contains(o);
    }

    @Override
    public Iterator<String> iterator() {
        return delegate.iterator();
    }

    @Override
    public Object[] toArray() {
        return delegate.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return delegate.toArray(a);
    }

    @Override
    public boolean add(String s) {
        return delegate.add(s);
    }

    @Override
    public boolean remove(Object o) {
        return delegate.remove(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return delegate.containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends String> c) {
        return delegate.addAll(c);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return delegate.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return delegate.retainAll(c);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    /**
     * Returns an immutable copy of current tags.
     */
    public Set<String> toSet() {
        return Set.copyOf(delegate);
    }
}
