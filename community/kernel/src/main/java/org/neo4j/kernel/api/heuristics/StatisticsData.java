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
package org.neo4j.kernel.api.heuristics;

import org.neo4j.graphdb.Direction;

public interface StatisticsData
{
    public static final int RELATIONSHIP_DEGREE_FOR_NODE_WITHOUT_LABEL = -1;

    /** Label id -> relative occurrence, value between 0 and 1. The total may be > 1, since labels may co-occur. */
    double labelDistribution(int labelId);

    /** Relationship type id -> relative occurrence, value between 0 and 1. The total adds up to 1 */
    double relationshipTypeDistribution(int relType);

    /** Relationship degree distribution for a label/rel type/direction triplet. */
    double degree( int labelId, int relType, Direction direction );

    /** Ratio of live nodes (i.e. nodes that are not deleted or corrupted) of all addressable nodes */
    double liveNodesRatio();

    /** Maximum number of addressable nodes */
    long maxAddressableNodes();
}
