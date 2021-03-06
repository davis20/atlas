package org.openstreetmap.atlas.utilities.arrays;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.openstreetmap.atlas.exception.CoreException;
import org.openstreetmap.atlas.utilities.scalars.Ratio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A LargeArray that can have more than {@link Integer}.MAX_VALUE items. It is backed by multiple
 * T[] arrays. Items can be added and gotten, but not removed. The get operations take long indices.
 *
 * @param <T>
 *            The type of items in the array
 * @author matthieun
 */
public abstract class LargeArray<T> implements Iterable<T>, Serializable
{
    private static final long serialVersionUID = -8827093729953143426L;
    private static final Logger logger = LoggerFactory.getLogger(LargeArray.class);

    private static final int DEFAULT_MEMORY_BLOCK_SIZE = 1024;

    private final List<PrimitiveArray<T>> arrays;
    private final long maximumSize;
    private long nextIndex = 0;
    private final int memoryBlockSize;
    private final int subArraySize;
    private String name = null;

    /**
     * Create a {@link LargeArray}
     *
     * @param maximumSize
     *            The maximum size of the array
     */
    public LargeArray(final long maximumSize)
    {
        this(maximumSize, DEFAULT_MEMORY_BLOCK_SIZE, Integer.MAX_VALUE);
    }

    /**
     * Create a {@link LargeArray}
     *
     * @param maximumSize
     *            The maximum size of the array
     * @param memoryBlockSize
     *            The initial block size for sub-array creation. If small, it might resize often, if
     *            large, there might be unused space.
     * @param subArraySize
     *            The maximum size of the sub arrays
     */
    public LargeArray(final long maximumSize, final int memoryBlockSize, final int subArraySize)
    {
        this.maximumSize = maximumSize;
        this.arrays = new ArrayList<>();
        this.memoryBlockSize = memoryBlockSize;
        this.subArraySize = subArraySize;
    }

    /**
     * Add an item to the array
     *
     * @param item
     *            The item to add
     * @throws CoreException
     *             if the array is full.
     */
    public void add(final T item)
    {
        if (this.nextIndex >= this.maximumSize)
        {
            throw new CoreException("The array is full. Cannot add " + item);
        }
        final int arrayIndex = arrayIndex(this.nextIndex);
        final int indexInside = indexInside(this.nextIndex);
        if (this.arrays.size() <= arrayIndex)
        {
            // Set an array size
            this.arrays.add(getNewArray(this.memoryBlockSize));
        }
        if (indexInside >= this.arrays.get(arrayIndex).size())
        {
            final PrimitiveArray<T> old = this.arrays.get(arrayIndex);
            final int maximumSizeFromDoubling = Math.min(2 * old.size(), this.subArraySize);
            final long filledArraysSize = filledArraysSize();
            final int maximumSizeFromTotal = (int) Math.min(this.maximumSize - filledArraysSize,
                    this.subArraySize);
            final int newSize = Math.min(maximumSizeFromDoubling, maximumSizeFromTotal);
            logger.warn("Resizing array {} of {} ({}), from {} to {}.", arrayIndex,
                    getName() == null ? super.toString() : getName(),
                    this.getClass().getSimpleName(), old.size(), newSize);
            this.arrays.set(arrayIndex, old.withNewSize(newSize));
        }
        this.arrays.get(arrayIndex(this.nextIndex)).set(indexInside(this.nextIndex), item);
        this.nextIndex++;
    }

    /**
     * Get an item from the array
     *
     * @param index
     *            The index to get
     * @return The item at the specified index
     * @throws CoreException
     *             If the index is out of bounds.
     */
    public T get(final long index)
    {
        if (index >= this.nextIndex)
        {
            throw new CoreException(index + " is out of bounds (size = " + size() + ")");
        }
        return this.arrays.get(arrayIndex(index)).get(indexInside(index));
    }

    /**
     * @return The name of this array
     */
    public String getName()
    {
        return this.name;
    }

    public boolean isEmpty()
    {
        return size() == 0;
    }

    @Override
    public Iterator<T> iterator()
    {
        return new Iterator<T>()
        {
            private final long totalSize = size();
            private long index = 0;

            @Override
            public boolean hasNext()
            {
                return this.index < this.totalSize;
            }

            @Override
            public T next()
            {
                return get(this.index++);
            }
        };
    }

    /**
     * Replace an already existing value
     *
     * @param index
     *            The index to replace the value at
     * @param item
     *            The new value to put
     */
    public void set(final long index, final T item)
    {
        if (index >= size())
        {
            throw new CoreException("Cannot replace an element that is not there. Index: " + index
                    + ", size: " + size());
        }
        this.arrays.get(arrayIndex(index)).set(indexInside(index), item);
    }

    public void setName(final String name)
    {
        this.name = name;
    }

    /**
     * @return The used size (which will always be smaller or equal to the size allocated in memory)
     */
    public long size()
    {
        return this.nextIndex;
    }

    @Override
    public String toString()
    {
        final StringBuilder builder = new StringBuilder();
        builder.append("[");
        builder.append(this.getClass().getSimpleName());
        builder.append(" ");
        this.forEach(t ->
        {
            if (builder.length() > 0)
            {
                builder.append(", ");
            }
            builder.append(t);
        });
        builder.append("]");
        return builder.toString();
    }

    /**
     * Trim this {@link LargeArray}. WARNING: As much as this might save memory on arrays filled a
     * small amount compared to the memoryBlockSize, it will resize the last {@link PrimitiveArray}
     * of this {@link LargeArray} which might take a lot of time, and (temporarily) a lot of memory.
     */
    public void trim()
    {
        trimIfLessFilledThan(Ratio.MAXIMUM);
    }

    /**
     * Trim this {@link LargeArray} if and only if the fill {@link Ratio} of the last
     * {@link PrimitiveArray} of this {@link LargeArray} is less than the provided {@link Ratio}
     *
     * @param ratio
     *            The provided reference {@link Ratio}
     */
    public void trimIfLessFilledThan(final Ratio ratio)
    {
        logger.trace("Trimming {} with Ratio {}", getName(), ratio);
        if (this.arrays.isEmpty())
        {
            return;
        }
        final int arrayIndex = this.arrays.size() - 1;
        final PrimitiveArray<T> last = this.arrays.get(arrayIndex);
        // Here nextIndex is actually the size, and not size-1
        final int indexInside = indexInside(this.nextIndex);
        if (Ratio.ratio((double) indexInside / last.size()).isLessThan(ratio))
        {
            this.arrays.set(arrayIndex, last.trimmed(indexInside));
        }
    }

    public LargeArray<T> withName(final String name)
    {
        setName(name);
        return this;
    }

    /**
     * @return the arrays, for testing
     */
    protected List<PrimitiveArray<T>> getArrays()
    {
        return this.arrays;
    }

    /**
     * Create a new primitive array of the Item.
     *
     * @param size
     *            The size of the array
     * @return The new primitive array.
     */
    protected abstract PrimitiveArray<T> getNewArray(int size);

    private int arrayIndex(final long index)
    {
        return (int) (index / this.subArraySize);
    }

    private long filledArraysSize()
    {
        if (this.arrays.size() <= 1)
        {
            return 0L;
        }
        long result = 0L;
        for (int i = 0; i < this.arrays.size() - 2; i++)
        {
            result += this.arrays.get(i).size();
        }
        return result;
    }

    private int indexInside(final long index)
    {
        return (int) (index % this.subArraySize);
    }
}
