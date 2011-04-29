/*
 * LensKit, a reference implementation of recommender algorithms.
 * Copyright 2010-2011 Regents of the University of Minnesota
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.grouplens.lenskit.knn.user;

import static java.lang.Math.max;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Collection;
import java.util.List;
import java.util.PriorityQueue;

import org.grouplens.common.cursors.Cursor;
import org.grouplens.common.cursors.Cursors;
import org.grouplens.lenskit.data.Rating;
import org.grouplens.lenskit.data.Ratings;
import org.grouplens.lenskit.data.context.RatingBuildContext;
import org.grouplens.lenskit.data.dao.RatingDataAccessObject;
import org.grouplens.lenskit.data.vector.ImmutableSparseVector;
import org.grouplens.lenskit.data.vector.MutableSparseVector;
import org.grouplens.lenskit.data.vector.SparseVector;
import org.grouplens.lenskit.knn.OptimizableVectorSimilarity;
import org.grouplens.lenskit.knn.Similarity;
import org.grouplens.lenskit.norm.UserRatingVectorNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Neighborhood finder that does a fresh search over the data source ever time.
 * 
 * <p>This rating vector has support for caching user rating vectors, where it
 * avoids rebuilding user rating vectors for users with no changed ratings. When
 * caching is enabled, it assumes that the underlying data is timestamped and
 * that the timestamps are well-behaved: if a rating has been added after the
 * currently cached rating vector was computed, then its timestamp is greater
 * than any timestamp seen while computing the cached vector.
 * 
 * <p>Currently, this cache is never cleared. This should probably be changed
 * sometime.
 * 
 * @author Michael Ekstrand <ekstrand@cs.umn.edu>
 *
 */
public class SimpleNeighborhoodFinder implements NeighborhoodFinder {
	private static final Logger logger = LoggerFactory.getLogger(SimpleNeighborhoodFinder.class);
    
    /**
     * Builder for creating SimpleNeighborhoodFinders.
     * 
     * @author Michael Ludwig <mludwig@cs.umn.edu>
     */
    public static class Builder extends AbstractNeighborhoodFinderBuilder<SimpleNeighborhoodFinder> {
        private boolean cacheUserRatingVectors = true;
        /**
         * Query whether user rating vectors are to be cached.
         * @return <tt>true</tt> if user rating vectors are cached in-memory.
         * @see #setCacheUserRatingVectors(boolean)
         */
        public boolean isCacheUserRatingVectors() {
            return cacheUserRatingVectors;
        }
        /**
         * Set whether to cache user rating vectors.  The default is <tt>true</tt>.
         * @param cacheUserRatingVectors <tt>true</tt> to cache rating vectors
         * in the resulting neighborhood finder.
         */
        public void setCacheUserRatingVectors(boolean cacheUserRatingVectors) {
            this.cacheUserRatingVectors = cacheUserRatingVectors;
        }
        
        @Override
        protected SimpleNeighborhoodFinder buildNew(RatingBuildContext context) {
            return new SimpleNeighborhoodFinder(context.getDAO(),
                                                neighborhoodSize, similarity,
                                                normalizerBuilder.build(context),
                                                cacheUserRatingVectors);
        }
    }
    
    static class CacheEntry {
        final long userId;
        final long lastRatingTimestamp;
        final int ratingCount;
        final ImmutableSparseVector ratings;
        
        CacheEntry(long uid, long ts, int count, SparseVector rv) {
            userId = uid;
            lastRatingTimestamp = ts;
            ratingCount = count;
            ratings = rv.immutable();
        }
    }
    
    private final RatingDataAccessObject dataSource;
    private final int neighborhoodSize;
    private final Similarity<? super SparseVector> similarity;
	private final UserRatingVectorNormalizer normalizer;
	private final Long2ObjectMap<CacheEntry> userVectorCache;

    /**
     * Construct a new user-user recommender.
     * @param data The data source to scan.
     * @param nnbrs The number of neighbors to consider for each item.
     * @param sim The similarity function to use.
     */
    protected SimpleNeighborhoodFinder(RatingDataAccessObject data, int nnbrs, 
                                       Similarity<? super SparseVector> sim,
                                       UserRatingVectorNormalizer norm, boolean cache) {
        dataSource = data;
        neighborhoodSize = nnbrs;
        similarity = sim;
        normalizer = norm;
        if (cache)
            userVectorCache = new Long2ObjectOpenHashMap<CacheEntry>(500);
        else
            userVectorCache = null;
    }

    /**
     * Find the neighbors for a user with respect to a collection of items.
     * For each item, the <var>neighborhoodSize</var> users closest to the
     * provided user are returned.
     *
     * @param uid The user ID.
     * @param ratings The user's ratings vector.
     * @param items The items for which neighborhoods are requested.
     * @return A mapping of item IDs to neighborhoods.
     */
    @Override
    public Long2ObjectMap<? extends Collection<Neighbor>> findNeighbors(long uid, SparseVector ratings, LongSet items) {
        Long2ObjectMap<PriorityQueue<Neighbor>> heaps =
            new Long2ObjectOpenHashMap<PriorityQueue<Neighbor>>(items != null ? items.size() : 100);
        
        MutableSparseVector nratings = ratings.mutableCopy();
        normalizer.normalize(uid, nratings);
        
        /* Find candidate neighbors. To reduce scanning, we limit users to those
         * rating target items. If the similarity is sparse and the user has
         * fewer items than target items, then we use the user's rated items to
         * attempt to minimize the number of users considered.
         */
        LongSet queryItems = items;
        if (similarity instanceof OptimizableVectorSimilarity<?> && ratings.size() < items.size()) {
            logger.trace("Using rating rather than query set");
            queryItems = ratings.keySet();
        }
        LongSet users = findRatingUsers(uid, queryItems);
        
        logger.trace("Found {} candidate neighbors", users.size());
        
        LongIterator uiter = users.iterator();
        while (uiter.hasNext()) {
            final long user = uiter.nextLong();
            SparseVector urv = getUserRatingVector(user);
            MutableSparseVector nurv = urv.mutableCopy();
            normalizer.normalize(user, nurv);
            
            final double sim = similarity.similarity(nratings, nurv);
            final Neighbor n = new Neighbor(user, urv.mutableCopy(), sim);

            LongIterator iit = urv.keySet().iterator();
            ITEMS: while (iit.hasNext()) {
                final long item = iit.nextLong();
                if (items != null && !items.contains(item))
                    continue ITEMS;

                PriorityQueue<Neighbor> heap = heaps.get(item);
                if (heap == null) {
                    heap = new PriorityQueue<Neighbor>(neighborhoodSize + 1,
                            Neighbor.SIMILARITY_COMPARATOR);
                    heaps.put(item, heap);
                }
                heap.add(n);
                if (heap.size() > neighborhoodSize) {
                    assert heap.size() == neighborhoodSize + 1;
                    heap.remove();
                }
            }
        }
        return heaps;
    }

    /**
     * Find all users who have rated any of a set of items.
     * @param user The current user's ID (excluded from the returned set).
     * @param itemSet The set of items to look for.
     * @return The set of all users who have rated at least one item in <var>itemSet</var>.
     */
    private LongSet findRatingUsers(long user, LongCollection itemSet) {
        LongSet users = new LongOpenHashSet(100);
        
        LongIterator items = itemSet.iterator();
        while (items.hasNext()) {
            final long item = items.nextLong();
            Cursor<Rating> ratings = dataSource.getItemRatings(item);
            try {
                for (Rating r: ratings) {
                    long uid = r.getUserId();
                    if (uid == user) continue;
                    users.add(uid);
                }
            } finally {
                ratings.close();
            }
        }
        
        return users;
    }
    
    /**
     * Look up the user's rating vector, using the cached version if possible.
     * @param user The user ID.
     * @return The user's rating vector.
     */
    private synchronized SparseVector getUserRatingVector(long user) {
        List<Rating> ratings = Cursors.makeList(dataSource.getUserRatings(user));
        CacheEntry e = userVectorCache.get(user);
        
        // check rating count
        if (e != null && e.ratingCount != ratings.size())
            e = null;
        
        // check max timestamp
        long ts = -1;
        if (e != null) {
            for (Rating r: ratings) {
                ts = max(ts, r.getTimestamp());
            }
            if (ts != e.lastRatingTimestamp)
                e = null;
        }
        
        // create new cache entry
        if (e == null) {
            SparseVector v = Ratings.userRatingVector(ratings);
            e = new CacheEntry(user, ts, ratings.size(), v);
            userVectorCache.put(user, e);
        }
        
        return e.ratings;
    }
}