/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.nioneo.store;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.neo4j.io.fs.StoreChannel;

/**
 * Manages {@link PersistenceWindow persistence windows} for a store. Each store
 * can configure how much memory it has for
 * {@link MappedPersistenceWindow memory mapped windows}. This class tries to
 * make the most efficient use of those windows by allocating them in such a way
 * that the most frequently used records/blocks (be it for read or write
 * operations) are encapsulated by a memory mapped persistence window.
 */
public class PersistenceWindowPool implements WindowPool
{
    interface Monitor {

        void recordStatistics( File storeName, int hit, int miss, int switches, int ooe );

        void recordStatus( File storeName, int brickCount, int brickSize, long availableMem, long size );

        void allocationError( File storeName, Throwable cause, String description );

        void insufficientMemoryForMapping( long availableMem, long wantedMem );

        public static final Monitor NULL = new Monitor()
        {
            @Override
            public void recordStatistics( File storeName, int hit, int miss, int switches, int ooe )
            {}

            @Override
            public void recordStatus( File storeName, int brickCount, int brickSize, long availableMem, long size )
            {}

            @Override
            public void allocationError( File storeName, Throwable cause, String description )
            {}

            @Override
            public void insufficientMemoryForMapping( long availableMem, long wantedMem )
            {}
        };
    }

    private static final int MAX_BRICK_COUNT = 100000;

    private final File storeName;
    // == recordSize
    private final int pageSize;
    private StoreChannel fileChannel;
    private final ConcurrentMap<Long,PersistenceRow> activeRowWindows;
    private long availableMem = 0;
    private long memUsed = 0;
    private int brickCount = 0;
    private int brickSize = 0;
    private BrickElement brickArray[] = new BrickElement[0];
    private int brickMiss = 0;

    static final int REFRESH_BRICK_COUNT = 50000;
    private final FileChannel.MapMode mapMode;

    private int hit = 0;
    private int miss = 0;
    private int switches = 0;
    private int ooe = 0;
    private boolean useMemoryMapped = true;

    private final boolean readOnly;

    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicInteger avertedRefreshes = new AtomicInteger();
    private final AtomicLong refreshTime = new AtomicLong();
    private final AtomicInteger refreshes = new AtomicInteger();
    private final Monitor monitor;
    private final BrickElementFactory brickFactory;

    /**
     * Create new pool for a store.
     *
     *
     * @param storeName
     *            Name of store that use this pool
     * @param pageSize
     *            The size of each record/block in the store
     * @param fileChannel
     *            A fileChannel to the store
     * @param mappedMem
     *            Number of bytes dedicated to memory mapped windows
     * @param activeRowWindows
     *            Data structure for storing active "row windows", generally just provide a concurrent hash map.
     * @throws java.io.IOException If unable to create pool
     */
    public PersistenceWindowPool( File storeName, int pageSize,
                                  StoreChannel fileChannel, long mappedMem,
                                  boolean useMemoryMappedBuffers, boolean readOnly,
                                  ConcurrentMap<Long, PersistenceRow> activeRowWindows,
                                  BrickElementFactory brickFactory,
                                  Monitor monitor ) throws IOException
    {
        this.storeName = storeName;
        this.pageSize = pageSize;
        this.fileChannel = fileChannel;
        this.availableMem = mappedMem;
        this.useMemoryMapped = useMemoryMappedBuffers;
        this.readOnly = readOnly;
        this.activeRowWindows = activeRowWindows;
        this.brickFactory = brickFactory;
        this.mapMode = readOnly ? MapMode.READ_ONLY : MapMode.READ_WRITE;
        this.monitor = monitor;
        setupBricks();
        dumpStatus();
    }

    /**
     * Acquires a windows for <CODE>position</CODE> and <CODE>operationType</CODE>
     * locking the window preventing other threads from using it.
     *
     * @param position
     *            The position the needs to be encapsulated by the window
     * @param operationType
     *            The type of operation (READ or WRITE)
     * @return A locked window encapsulating the position
     */
    @Override
    public PersistenceWindow acquire( long position, OperationType operationType ) throws IOException
    {
        LockableWindow window = null;
        if ( brickMiss >= REFRESH_BRICK_COUNT )
        {
            refreshBricks();
        }
        BrickElement theBrick = null;
        while ( window == null )
        {
            if ( brickSize > 0 )
            {
                int brickIndex = positionToBrickIndex( position );
                if ( brickIndex >= brickArray.length )
                {
                    expandBricks( brickIndex + 1 );
                }
                theBrick = brickArray[brickIndex];
                window = theBrick.getAndMarkWindow();
            }
            if ( window == null )
            {
                // There was no mapped window for this brick. Go for active window instead.

                // Should be AtomicIntegers, but it's completely OK to miss some
                // updates for these statistics, right?
                miss++;
                brickMiss++;

                // Lock-free implementation of instantiating an active window for this position
                // See if there's already an active window for this position
                PersistenceRow dpw = activeRowWindows.get( position );
                if ( dpw != null && dpw.markAsInUse() )
                {   // ... there was and we managed to mark it as in use
                    window = dpw;
                    break;
                }

                // Either there was no active window for this position or it got
                // closed right before we managed to mark it as in use.
                // Either way instantiate a new active window for this position
                dpw = new PersistenceRow( position, pageSize, fileChannel );
                PersistenceRow existing = activeRowWindows.putIfAbsent( position, dpw );
                if ( existing == null )
                {
                    // No other thread managed to create an active window for
                    // this position before us.
                    window = dpw;
                }
                else
                {
                    // Someone else put it there before us. Close this row
                    // which was unnecessarily opened. The next go in this loop
                    // will get that one instead.
                    dpw.close();
                    if(theBrick != null)
                    {
                        // theBrick may be null here if brick size is 0.
                        theBrick.unLock();
                    }
                }
            }
            else
            {
                hit++;
            }
        }

        window.lock( operationType );
        return window;
    }

    private int positionToBrickIndex( long position )
    {
        return (int) (position * pageSize / brickSize);
    }

    private long brickIndexToPosition( int brickIndex )
    {
        return (long) brickIndex * brickSize / pageSize;
    }

    void dumpStatistics()
    {
        monitor.recordStatistics( storeName, hit, miss, switches, ooe );
//        log.info( storeName + " hit=" + hit + " miss=" + miss + " switches="
//                        + switches + " ooe=" + ooe );
    }

    /**
     * Releases a window used for an operation back to the pool and unlocks it
     * so other threads may use it.
     *
     * @param window
     *            The window to be released
     */
    @Override
    public void release( PersistenceWindow window ) throws IOException
    {
        try
        {
            if ( window instanceof PersistenceRow )
            {
                PersistenceRow dpw = (PersistenceRow) window;
                try
                {
                    // If the corresponding window has been instantiated while we had
                    // this active row we need to hand over the changes to that
                    // window if the window isn't memory mapped.
                    if ( brickSize > 0 && dpw.isDirty() )
                    {
                        applyChangesToWindowIfNecessary( dpw );
                    }
                    if ( dpw.writeOutAndCloseIfFree( readOnly ) )
                    {
                        activeRowWindows.remove( dpw.position(), dpw );
                    }
                    else
                    {
                        dpw.reset();
                    }
                }
                finally
                {
                    if ( brickSize > 0 )
                    {
                        int brickIndex = positionToBrickIndex( dpw.position() );
                        BrickElement theBrick = brickArray[brickIndex];
                        theBrick.unLock();
                    }
                }
            }
        }
        finally
        {
            ((LockableWindow) window).unLock();
        }
    }

    private void applyChangesToWindowIfNecessary( PersistenceRow dpw )
    {
        int brickIndex = positionToBrickIndex( dpw.position() );
        LockableWindow existingBrickWindow = brickIndex < brickArray.length ?
                brickArray[brickIndex].getWindow() : null;
        if ( existingBrickWindow != null && !(existingBrickWindow instanceof MappedPersistenceWindow) &&
                existingBrickWindow.markAsInUse() )
        {
            // There is a non-mapped brick window here, let's have it
            // know about my changes.
            existingBrickWindow.lock( OperationType.WRITE );
            try
            {
                existingBrickWindow.acceptContents( dpw );
            }
            finally
            {
                existingBrickWindow.unLock();
            }
        }
    }

    @Override
    public synchronized void close() throws IOException
    {
        flushAll();
        for ( BrickElement element : brickArray )
        {
            if ( element.getWindow() != null )
            {
                element.getWindow().close();
                element.setWindow( null );
            }
        }
        fileChannel = null;
        activeRowWindows.clear();
        dumpStatistics();
    }

    @Override
    public void flushAll() throws IOException
    {
        if ( readOnly )
        {
            return;
        }

        try
        {
            for ( BrickElement element : brickArray )
            {
                PersistenceWindow window = element.getWindow();
                if ( window != null )
                {
                    window.force();
                }
            }
            fileChannel.force( false );
        }
        catch ( IOException e )
        {
            throw new IOException(
                "Failed to flush file channel " + storeName, e );
        }
    }

    /**
     * Initial setup of bricks based on the size of the given channel and
     * available memory to map.
     */
    private void setupBricks() throws IOException
    {
        long fileSize = -1;
        try
        {
            fileSize = fileChannel.size();
        }
        catch ( IOException e )
        {
            throw new IOException(
                "Unable to get file size for " + storeName, e );
        }
        if ( pageSize == 0 )
        {
            return;
        }

        // If we can't fit even 10 blocks in available memory don't even try
        // to use available memory.
        if(availableMem > 0 && availableMem < pageSize * 10l )
        {
            monitor.insufficientMemoryForMapping( availableMem, pageSize * 10 );
//            log.warn( "[" + storeName + "] " + "Unable to use " + availableMem
//                        + "b as memory mapped windows, need at least " + pageSize * 10
//                        + "b (block size * 10)" );
//            log.warn( "[" + storeName + "] " + "Memory mapped windows have been turned off" );
            availableMem = 0;
            brickCount = 0;
            brickSize = 0;
            return;
        }
        if ( availableMem > 0 && fileSize > 0 )
        {
            double ratio = (availableMem + 0.0d) / fileSize;
            if ( ratio >= 1 )
            {
                brickSize = (int) (availableMem / 1000);
                if ( brickSize < 0 )
                {
                    brickSize = Integer.MAX_VALUE;
                }
                brickSize = (brickSize / pageSize) * pageSize;
                if ( brickSize == 0 )
                {
                    brickSize = pageSize;
                }
                brickCount = (int) (fileSize / brickSize);
            }
            else
            {
                brickCount = (int) (1000.0d / ratio);
                if ( brickCount > MAX_BRICK_COUNT )
                {
                    brickCount = MAX_BRICK_COUNT;
                }
                if ( fileSize / brickCount > availableMem )
                {
                    monitor.insufficientMemoryForMapping( availableMem, fileSize / brickCount );
//                    log.warn( "[" + storeName + "] " + "Unable to use " + (availableMem / 1024)
//                                    + "kb as memory mapped windows, need at least "
//                                    + (fileSize / brickCount / 1024) + "kb" );
//                    log.warn( "[" + storeName + "] " + "Memory mapped windows have been turned off" );
                    availableMem = 0;
                    brickCount = 0;
                    brickSize = 0;
                    return;
                }
                brickSize = (int) (fileSize / brickCount);
                if ( brickSize < 0 )
                {
                    brickSize = Integer.MAX_VALUE;
                    brickSize = (brickSize / pageSize) * pageSize;
                    brickCount = (int) (fileSize / brickSize);
                }
                else if ( brickSize < pageSize )
                {
                    brickSize = pageSize;
                }
                else
                {
                    brickSize = (brickSize / pageSize) * pageSize;
                }
                assert brickSize >= pageSize;
            }
        }
        else if ( availableMem > 0 )
        {
            brickSize = (int) (availableMem / 100);
            if ( brickSize < 0 )
            {
                brickSize = Integer.MAX_VALUE;
            }
            brickSize = (brickSize / pageSize) * pageSize;
        }
        brickArray = new BrickElement[brickCount];
        for ( int i = 0; i < brickCount; i++ )
        {
            BrickElement element = brickFactory.create( i );
            brickArray[i] = element;
        }
    }

    /**
     * Called during expanding of bricks where we see that we use too much
     * memory and need to release some windows.
     *
     * @param nr the number of windows to free.
     */
    private void freeWindows( int nr ) throws IOException
    {
        // Only called from expandBricks, so we're under a lock here
        if ( brickSize <= 0 )
        {
            // memory mapped turned off
            return;
        }
        ArrayList<BrickElement> mappedBricks = new ArrayList<>();
        for ( int i = 0; i < brickCount; i++ )
        {
            BrickElement be = brickArray[i];
            if ( be.getWindow() != null )
            {
                be.snapshotHitCount();
                mappedBricks.add( be );
            }
        }
        Collections.sort( mappedBricks, BRICK_SORTER );
        for ( int i = 0; i < nr && i < mappedBricks.size(); i++ )
        {
            BrickElement mappedBrick = mappedBricks.get( i );
            LockableWindow window = mappedBrick.getWindow();
            if ( window.writeOutAndCloseIfFree( readOnly ) )
            {
                mappedBrick.setWindow( null );
                memUsed -= brickSize;
            }
        }
    }

    /**
     * Go through the bricks and see if they are optimally placed, and change
     * accordingly. This happens whenever we see that there has been a certain
     * amount of brick misses since the last refresh.
     */
    private void refreshBricks() throws IOException
    {
        if ( brickMiss < REFRESH_BRICK_COUNT || brickSize <= 0 )
        {
            return;
        }

        if ( refreshing.compareAndSet( false, true ) )
        {
            // No one is doing refresh right now, go ahead and do it
            try
            {
                long t = System.currentTimeMillis();
                doRefreshBricks();
                refreshes.incrementAndGet();
                refreshTime.addAndGet( System.currentTimeMillis()-t );
            }
            finally
            {
                refreshing.set( false );
            }
        }
        else
        {
            // Another thread is doing refresh right now, trust it to refresh the bricks
            // and just go about my business.
            avertedRefreshes.incrementAndGet();
        }
    }

    private synchronized void doRefreshBricks() throws IOException
    {
        brickMiss = 0;
        List<BrickElement> mappedBricks = new ArrayList<>();
        List<BrickElement> unmappedBricks = new ArrayList<>();
        gatherMappedVersusUnmappedWindows( mappedBricks, unmappedBricks );

        // Fill up unused memory, i.e. map unmapped bricks as much as available memory allows
        // and request patterns signals. Start the loop from the end of the array where the
        // bricks with highest hit ratio are.
        int unmappedIndex = unmappedBricks.size() - 1;
        while ( memUsed + brickSize <= availableMem && unmappedIndex >= 0 )
        {
            BrickElement unmappedBrick = unmappedBricks.get( unmappedIndex-- );
            if ( unmappedBrick.getHit() == 0 )
            {
                // We have more memory available, but no more windows have actually
                // been requested so don't map unused random windows.
                return;
            }

            allocateNewWindow( unmappedBrick );
        }

        // Switch bad/unused mappings. Start iterating over mapped bricks
        // from the beginning (those with lowest hit ratio) and unmapped from the end
        // (or rather where the fill-up-unused-memory loop above left off) where we've
        // got the unmapped bricks with highest hit ratio.
        int mappedIndex = 0;
        while ( unmappedIndex >= 0 && mappedIndex < mappedBricks.size() )
        {
            BrickElement mappedBrick = mappedBricks.get( mappedIndex++ );
            BrickElement unmappedBrick = unmappedBricks.get( unmappedIndex-- );
            if ( mappedBrick.getHit() >= unmappedBrick.getHit() )
            {
                // We've passed a point where we don't have any unmapped brick
                // with a higher hit ratio then the lowest mapped brick. We're done.
                break;
            }

            LockableWindow window = mappedBrick.getWindow();
            if ( window.writeOutAndCloseIfFree( readOnly ) )
            {
                mappedBrick.setWindow( null );
                memUsed -= brickSize;
                if ( allocateNewWindow( unmappedBrick ) )
                {
                    switches++;
                }
            }
        }
    }

    /**
     * Goes through all bricks in this pool and divides them between mapped and unmapped,
     * i.e. those with a mapped persistence window assigned to it and those without.
     *
     * The two {@link List lists} coming back are also sorted where the first element
     * has got the lowest {@link BrickElement#getHit()} ratio, and the last the highest.
     *
     * @return all bricks in this pool divided into mapped and unmapped.
     */
    private void gatherMappedVersusUnmappedWindows(
            List<BrickElement> mappedBricks,
            List<BrickElement> unmappedBricks )
    {
        for ( int i = 0; i < brickCount; i++ )
        {
            BrickElement be = brickArray[i];
            be.snapshotHitCount();
            if ( be.getWindow() != null )
            {
                mappedBricks.add( be );
            }
            else
            {
                unmappedBricks.add( be );
            }
            be.refresh();
        }
        Collections.sort( unmappedBricks, BRICK_SORTER );
        Collections.sort( mappedBricks, BRICK_SORTER );
    }

    /**
     * Called every time we request a brick that has a greater index than
     * the current brick count. This happens as the underlying file channel
     * grows as new blocks/records are added to it.
     *
     * @param newBrickCount the size to expand the brick count to.
     */
    private synchronized void expandBricks( int newBrickCount ) throws IOException
    {
        if ( newBrickCount > brickCount )
        {
            BrickElement tmpArray[] = new BrickElement[newBrickCount];
            System.arraycopy( brickArray, 0, tmpArray, 0, brickArray.length );
            if ( memUsed + brickSize >= availableMem )
            {
                freeWindows( 1 );
            }
            for ( int i = brickArray.length; i < tmpArray.length; i++ )
            {
                BrickElement be = brickFactory.create( i );
                tmpArray[i] = be;
                if ( memUsed + brickSize <= availableMem )
                {
                    allocateNewWindow( be );
                }
            }
            brickArray = tmpArray;
            brickCount = tmpArray.length;
        }
    }

    /**
     * Allocates a new persistence window for the {@code brick}. Such an
     * allocation may fail with memory problems and such an error will be
     * caught and logged as well as a counter incremented. It's OK if
     * a memory mapping fails, because we can fall back on temporary
     * {@link PersistenceRow persistence rows}.
     *
     * @param brick the {@link BrickElement} to allocate a new window for.
     * @return {@code true} if the window was successfully allocated,
     * otherwise {@code false}.
     */
    boolean allocateNewWindow( BrickElement brick ) throws IOException
    {
        try
        {
            int maxWindowMappingAttempts = 5;
            for ( int attempt = 0; attempt < maxWindowMappingAttempts; attempt++ )
            {
                /*
                 * This is a busy wait, given that rows are kept for a very short time. What we do is lock the brick so
                 * no rows can be mapped over it, then we wait for every row mapped over it to be released (which can
                 * happen because releasing the row does not acquire a lock). When that happens, we can go ahead and
                 * map the window here. If a thread was waiting for this lock, after it acquires it, it will discover
                 * the window in place, so it will never allocate a row.
                 */
                synchronized ( brick )
                {
                    if ( brick.lockCount.get() == 0 )
                    {
                        if ( useMemoryMapped )
                        {
                            LockableWindow window = new MappedPersistenceWindow(
                                    brickIndexToPosition( brick.index() ), pageSize,
                                    brickSize, fileChannel, mapMode );
                            brick.setWindow( window );
                        }
                        else
                        {
                            PlainPersistenceWindow window =
                                    new PlainPersistenceWindow(
                                            brickIndexToPosition( brick.index() ),
                                            pageSize, brickSize, fileChannel );
                            window.readFullWindow();
                            brick.setWindow( window );
                        }
                        memUsed += brickSize;
                        return true;
                    }
                }
                // Locks are still held on this brick, give others some breathing space to release their locks:
                Thread.yield();
            }
            return false;
        }
        catch ( MappedMemException e )
        {
            ooe++;
//            log.warn( "[" + storeName + "] " + "Unable to memory map", e );
            monitor.allocationError( storeName, e, "Unable to memory map" );
        }
        catch ( OutOfMemoryError e )
        {
            ooe++;
            monitor.allocationError( storeName, e, "Unable to allocate direct buffer" );
//            log.warn( "[" + storeName + "] " + "Unable to allocate direct buffer", e );
        }
        return false;
    }

    private void dumpStatus() throws IOException
    {
        try
        {
            monitor.recordStatus( storeName, brickCount, brickSize, availableMem, fileChannel.size() );
//            log.info( "[" + storeName + "] brickCount=" + brickCount
//                            + " brickSize=" + brickSize + "b mappedMem=" + availableMem
//                            + "b (storeSize=" + fileChannel.size() + "b)" );
        }
        catch ( IOException e )
        {
            throw new IOException(
                "Unable to get file size for " + storeName, e );
        }
    }

    @Override
    public WindowPoolStats getStats()
    {
        int avgRefreshTime = refreshes.get() == 0 ? 0 : (int)(refreshTime.get()/refreshes.get());
        return new WindowPoolStats( storeName, availableMem, memUsed, brickCount,
                brickSize, hit, miss, ooe, switches, avgRefreshTime, refreshes.get(), avertedRefreshes.get() );
    }

    /**
     * Sorts {@link BrickElement} by their {@link BrickElement#getHit()} ratio.
     * Lowest hit ratio will make that brick end up at a lower index in list,
     * so the least requested will be at the beginning and the most requested at the end.
     */
    private static final Comparator<BrickElement> BRICK_SORTER = new Comparator<BrickElement>()
    {
        @Override
        public int compare( BrickElement o1, BrickElement o2 )
        {
            return o1.getHitCountSnapshot() - o2.getHitCountSnapshot();
        }
    };
}