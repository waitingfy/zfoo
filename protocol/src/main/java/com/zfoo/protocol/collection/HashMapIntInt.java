/*
 * Copyright (C) 2020 The zfoo Authors
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.zfoo.protocol.collection;

import com.zfoo.protocol.util.StringUtils;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.internal.MathUtil;

import java.util.*;


/**
 * @author godotg
 * @version 3.0
 */
public class HashMapIntInt implements Map<Integer, Integer> {

    public static final byte FREE = 0;
    public static final byte REMOVED = 1;
    public static final byte FILLED = 2;

    private int[] keys;
    private int[] values;
    private byte[] statuses;
    private int size;
    private int maxSize;
    private int mask;

    /**
     * Calculates the maximum size allowed before rehashing.
     */
    public static int calcMaxSize(int capacity) {
        // Clip the upper bound so that there will always be at least one available slot.
        int upperBound = capacity - 1;
        return Math.min(upperBound, (int) (capacity * IntObjectHashMap.DEFAULT_LOAD_FACTOR));
    }

    /**
     * Get the next sequential index after index and wraps if necessary.
     */
    public static int probeNext(int index, int mask) {
        // The array lengths are always a power of two, so we can use a bitmask to stay inside the array bound
        return (index + 1) & mask;
    }

    public HashMapIntInt() {
        this(IntObjectHashMap.DEFAULT_CAPACITY);
    }

    public HashMapIntInt(int initialCapacity) {
        var capacity = MathUtil.safeFindNextPositivePowerOfTwo(initialCapacity);
        initCapacity(capacity);
    }

    private void initCapacity(int capacity) {
        mask = capacity - 1;

        keys = new int[capacity];
        values = new int[capacity];
        statuses = new byte[capacity];

        maxSize = calcMaxSize(capacity);
    }

    private void ensureCapacity() {
        if (size > maxSize) {
            if (keys.length == Integer.MAX_VALUE) {
                throw new IllegalStateException("Max capacity reached at size=" + size);
            }
            // Double the capacity.
            rehash(keys.length << 1);
        }
    }


    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    public boolean containsKeyPrimitive(int key) {
        return indexOf(key) >= 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return containsKeyPrimitive(ArrayUtils.intValue((Integer) key));
    }

    @Override
    public boolean containsValue(Object value) {
        return containsValuePrimitive(ArrayUtils.intValue((Integer) value));
    }

    public boolean containsValuePrimitive(int value) {
        for (var i = 0; i < statuses.length; i++) {
            if (statuses[i] == FILLED && values[i] == value) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Integer get(Object key) {
        var index = indexOf(ArrayUtils.intValue((Integer) key));
        return index == -1 ? null : values[index];
    }

    @Override
    public Integer put(Integer key, Integer value) {
        return putPrimitive(ArrayUtils.intValue(key), ArrayUtils.intValue(value));
    }

    public Integer putPrimitive(int key, int value) {
        var startIndex = hashIndex(key);
        var index = startIndex;

        var firstRemoveIndex = -1;
        for (; ; ) {
            var status = statuses[index];
            if (status == FREE) {
                index = firstRemoveIndex < 0 ? index : firstRemoveIndex;
                set(index, key, value, FILLED);
                size++;
                ensureCapacity();
                return null;
            } else if (status == REMOVED) {
                firstRemoveIndex = firstRemoveIndex < 0 ? index : firstRemoveIndex;
            } else if (keys[index] == key) { // status == FILLED
                // Found existing entry with this key, just replace the value.
                var previousValue = values[index];
                values[index] = value;
                return previousValue;
            }

            // Conflict, keep probing ...
            if ((index = probeNext(index, mask)) == startIndex) {
                if (firstRemoveIndex < 0) {
                    throw new IllegalStateException("Unable to insert, the map was full at MAX_ARRAY_SIZE and couldn't grow");
                } else {
                    set(firstRemoveIndex, key, value, FILLED);
                    size++;
                    ensureCapacity();
                    return null;
                }
            }
        }
    }

    @Override
    public Integer remove(Object key) {
        return removePrimitive(ArrayUtils.intValue((Integer) key));
    }

    public Integer removePrimitive(int key) {
        var index = indexOf(key);
        if (index == -1) {
            return null;
        }
        var prev = values[index];
        removeAt(index);
        return prev;
    }

    private void removeAt(int index) {
        set(index, 0, 0, REMOVED);
        size--;
    }

    @Override
    public void putAll(Map<? extends Integer, ? extends Integer> m) {
        for (Entry<? extends Integer, ? extends Integer> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void clear() {
        Arrays.fill(keys, 0);
        Arrays.fill(values, 0);
        Arrays.fill(statuses, FREE);
        size = 0;
    }

    @Override
    public Set<Integer> keySet() {
        return new KeySet();
    }

    @Override
    public Collection<Integer> values() {
        return new ValueSet();
    }

    @Override
    public Set<Entry<Integer, Integer>> entrySet() {
        return new EntrySet();
    }

    private int hashIndex(int key) {
        return key & mask;
    }

    private void set(int index, int key, int value, byte status) {
        keys[index] = key;
        values[index] = value;
        statuses[index] = status;
    }

    private void rehash(int newCapacity) {
        var oldKeys = keys;
        var oldValues = values;
        var oldStatuses = statuses;

        initCapacity(newCapacity);

        for (var i = 0; i < oldStatuses.length; ++i) {
            var oldStatus = oldStatuses[i];
            if (oldStatus == FILLED) {
                var oldKey = oldKeys[i];
                var oldValue = oldValues[i];
                int index = hashIndex(oldKey);

                for (; ; ) {
                    if (statuses[index] == FREE) {
                        set(index, oldKey, oldValue, FILLED);
                        break;
                    }

                    index = probeNext(index, mask);
                }
            }
        }
    }

    private int indexOf(int key) {
        int startIndex = hashIndex(key);
        int index = startIndex;

        for (; ; ) {
            var status = statuses[index];
            if (status == FREE) {
                // It's available, so no chance that this value exists anywhere in the map.
                return -1;
            }
            if (key == keys[index] && status == FILLED) {
                return index;
            }

            // Conflict, keep probing ...
            if ((index = probeNext(index, mask)) == startIndex) {
                return -1;
            }
        }
    }

    private class PrimitiveEntry implements Entry<Integer, Integer> {
        int entryIndex;

        PrimitiveEntry(int entryIndex) {
            this.entryIndex = entryIndex;
        }

        @Override
        public Integer getKey() {
            return keys[entryIndex];
        }

        @Override
        public Integer getValue() {
            return values[entryIndex];
        }

        @Override
        public Integer setValue(Integer value) {
            var prevValue = ArrayUtils.intValue(values[entryIndex]);
            values[entryIndex] = value;
            return prevValue;
        }
    }

    private class FastIterator implements Iterator<Entry<Integer, Integer>> {
        int lastCursor = -1;
        int cursor = -1;

        private void scanNext() {
            while (++cursor != statuses.length && statuses[cursor] != FILLED) {
            }
        }

        @Override
        public boolean hasNext() {
            if (cursor == -1) {
                scanNext();
            }
            return cursor != statuses.length;
        }

        @Override
        public Entry<Integer, Integer> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            lastCursor = cursor;
            scanNext();

            return new PrimitiveEntry(lastCursor);
        }

        @Override
        public void remove() {
            if (lastCursor == -1) {
                throw new IllegalStateException("next must be called before each remove.");
            }
            removeAt(lastCursor);
            cursor = -1;
            lastCursor = -1;
        }
    }

    private final class KeySet extends AbstractSet<Integer> {
        FastIterator fastIterator = new FastIterator();

        @Override
        public Iterator<Integer> iterator() {
            return new Iterator<Integer>() {
                @Override
                public boolean hasNext() {
                    return fastIterator.hasNext();
                }

                @Override
                public Integer next() {
                    return fastIterator.next().getKey();
                }

                @Override
                public void remove() {
                    fastIterator.remove();
                }
            };
        }

        @Override
        public int size() {
            return HashMapIntInt.this.size();
        }
    }

    private final class ValueSet extends AbstractSet<Integer> {
        FastIterator fastIterator = new FastIterator();

        @Override
        public Iterator<Integer> iterator() {
            return new Iterator<Integer>() {
                @Override
                public boolean hasNext() {
                    return fastIterator.hasNext();
                }

                @Override
                public Integer next() {
                    return fastIterator.next().getValue();
                }

                @Override
                public void remove() {
                    fastIterator.remove();
                }
            };
        }

        @Override
        public int size() {
            return HashMapIntInt.this.size();
        }
    }

    private final class EntrySet extends AbstractSet<Entry<Integer, Integer>> {
        @Override
        public Iterator<Entry<Integer, Integer>> iterator() {
            return new FastIterator();
        }

        @Override
        public int size() {
            return HashMapIntInt.this.size();
        }
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return StringUtils.EMPTY_JSON;
        }
        var builder = new StringBuilder(4 * size);
        builder.append('{');
        var first = true;
        for (int i = 0; i < values.length; ++i) {
            if (statuses[i] != FILLED) {
                continue;
            }
            if (!first) {
                builder.append(", ");
            }
            builder.append(keys[i]).append('=').append(values[i]);
            first = false;
        }
        return builder.append('}').toString();
    }
}
