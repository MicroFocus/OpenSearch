/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.routing;

import org.opensearch.action.admin.indices.alias.IndicesAliasesRequest.AliasActions;
import org.opensearch.action.support.IndicesOptions;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.common.Priority;
import org.opensearch.test.OpenSearchIntegTestCase;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.opensearch.common.util.set.Sets.newHashSet;
import static org.opensearch.index.query.QueryBuilders.queryStringQuery;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class AliasResolveRoutingIT extends OpenSearchIntegTestCase {

    // see https://github.com/elastic/elasticsearch/issues/13278
    public void testSearchClosedWildcardIndex() throws ExecutionException, InterruptedException {
        createIndex("test-0");
        createIndex("test-1");
        ensureGreen();
        client().admin().indices().prepareAliases().addAlias("test-0", "alias-0").addAlias("test-1", "alias-1").get();
        client().admin().indices().prepareClose("test-1").get();
        indexRandom(true, client().prepareIndex("test-0", "type1", "1").setSource("field1", "the quick brown fox jumps"),
            client().prepareIndex("test-0", "type1", "2").setSource("field1", "quick brown"),
            client().prepareIndex("test-0", "type1", "3").setSource("field1", "quick"));
        refresh("test-*");
        assertHitCount(
                client()
                        .prepareSearch()
                        .setIndices("alias-*")
                        .setIndicesOptions(IndicesOptions.lenientExpandOpen())
                        .setQuery(queryStringQuery("quick"))
                        .get(),
                3L);
    }

    public void testResolveIndexRouting() {
        createIndex("test1");
        createIndex("test2");
        client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setWaitForGreenStatus().execute().actionGet();

        client().admin().indices().prepareAliases()
                .addAliasAction(AliasActions.add().index("test1").alias("alias"))
                .addAliasAction(AliasActions.add().index("test1").alias("alias10").routing("0"))
                .addAliasAction(AliasActions.add().index("test1").alias("alias110").searchRouting("1,0"))
                .addAliasAction(AliasActions.add().index("test1").alias("alias12").routing("2"))
                .addAliasAction(AliasActions.add().index("test2").alias("alias20").routing("0"))
                .addAliasAction(AliasActions.add().index("test2").alias("alias21").routing("1"))
                .addAliasAction(AliasActions.add().index("test1").alias("alias0").routing("0"))
                .addAliasAction(AliasActions.add().index("test2").alias("alias0").routing("0")).get();

        assertThat(clusterService().state().metadata().resolveIndexRouting(null, "test1"), nullValue());
        assertThat(clusterService().state().metadata().resolveIndexRouting(null, "alias"), nullValue());

        assertThat(clusterService().state().metadata().resolveIndexRouting(null, "test1"), nullValue());
        assertThat(clusterService().state().metadata().resolveIndexRouting(null, "alias10"), equalTo("0"));
        assertThat(clusterService().state().metadata().resolveIndexRouting(null, "alias20"), equalTo("0"));
        assertThat(clusterService().state().metadata().resolveIndexRouting(null, "alias21"), equalTo("1"));
        assertThat(clusterService().state().metadata().resolveIndexRouting("3", "test1"), equalTo("3"));
        assertThat(clusterService().state().metadata().resolveIndexRouting("0", "alias10"), equalTo("0"));

        try {
            clusterService().state().metadata().resolveIndexRouting("1", "alias10");
            fail("should fail");
        } catch (IllegalArgumentException e) {
            // all is well, we can't have two mappings, one provided, and one in the alias
        }

        try {
            clusterService().state().metadata().resolveIndexRouting(null, "alias0");
            fail("should fail");
        } catch (IllegalArgumentException ex) {
            // Expected
        }
    }

    public void testResolveSearchRouting() {
        createIndex("test1");
        createIndex("test2");
        createIndex("test3");
        client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setWaitForGreenStatus().execute().actionGet();

        client().admin().indices().prepareAliases()
                .addAliasAction(AliasActions.add().index("test1").alias("alias"))
                .addAliasAction(AliasActions.add().index("test1").alias("alias10").routing("0"))
                .addAliasAction(AliasActions.add().index("test2").alias("alias20").routing("0"))
                .addAliasAction(AliasActions.add().index("test2").alias("alias21").routing("1"))
                .addAliasAction(AliasActions.add().index("test1").alias("alias0").routing("0"))
                .addAliasAction(AliasActions.add().index("test2").alias("alias0").routing("0"))
                .addAliasAction(AliasActions.add().index("test3").alias("alias3tw").routing("tw "))
                .addAliasAction(AliasActions.add().index("test3").alias("alias3ltw").routing(" ltw "))
                .addAliasAction(AliasActions.add().index("test3").alias("alias3lw").routing(" lw")).get();

        ClusterState state = clusterService().state();
        IndexNameExpressionResolver indexNameExpressionResolver = internalCluster().getInstance(IndexNameExpressionResolver.class);
        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, null, "alias"), nullValue());
        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, "0,1", "alias"), equalTo(newMap("test1", newSet("0", "1"))));
        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, null, "alias10"), equalTo(newMap("test1", newSet("0"))));
        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, null, "alias10"), equalTo(newMap("test1", newSet("0"))));
        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, "0", "alias10"), equalTo(newMap("test1", newSet("0"))));
        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, "1", "alias10"), nullValue());
        assertThat(
                indexNameExpressionResolver.resolveSearchRouting(state, null, "alias0"),
                equalTo(newMap("test1", newSet("0"), "test2", newSet("0"))));

        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, null, new String[]{"alias10", "alias20"}),
                equalTo(newMap("test1", newSet("0"), "test2", newSet("0"))));
        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, null, new String[]{"alias10", "alias21"}),
                equalTo(newMap("test1", newSet("0"), "test2", newSet("1"))));
        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, null, new String[]{"alias20", "alias21"}),
                equalTo(newMap("test2", newSet("0", "1"))));
        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, null, new String[]{"test1", "alias10"}), nullValue());
        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, null, new String[]{"alias10", "test1"}), nullValue());


        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, "0", new String[]{"alias10", "alias20"}),
                equalTo(newMap("test1", newSet("0"), "test2", newSet("0"))));
        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, "0,1", new String[]{"alias10", "alias20"}),
                equalTo(newMap("test1", newSet("0"), "test2", newSet("0"))));
        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, "1", new String[]{"alias10", "alias20"}), nullValue());
        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, "0", new String[]{"alias10", "alias21"}),
                equalTo(newMap("test1", newSet("0"))));
        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, "1", new String[]{"alias10", "alias21"}),
                equalTo(newMap("test2", newSet("1"))));
        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, "0,1,2", new String[]{"alias10", "alias21"}),
                equalTo(newMap("test1", newSet("0"), "test2", newSet("1"))));
        assertThat(indexNameExpressionResolver.resolveSearchRouting(state, "0,1,2", new String[]{"test1", "alias10", "alias21"}),
                equalTo(newMap("test1", newSet("0", "1", "2"), "test2", newSet("1"))));

        assertThat(
                indexNameExpressionResolver.resolveSearchRouting(state, "tw , ltw , lw", "test1"),
                equalTo(newMap("test1", newSet("tw ", " ltw ", " lw"))));
        assertThat(
                indexNameExpressionResolver.resolveSearchRouting(state, "tw , ltw , lw", "alias3tw"),
                equalTo(newMap("test3", newSet("tw "))));
        assertThat(
                indexNameExpressionResolver.resolveSearchRouting(state, "tw , ltw , lw", "alias3ltw"),
                equalTo(newMap("test3", newSet(" ltw "))));
        assertThat(
                indexNameExpressionResolver.resolveSearchRouting(state, "tw , ltw , lw", "alias3lw"),
                equalTo(newMap("test3", newSet(" lw"))));
        assertThat(
                indexNameExpressionResolver.resolveSearchRouting(state, "0,tw , ltw , lw", "test1", "alias3ltw"),
                equalTo(newMap("test1", newSet("0", "tw ", " ltw ", " lw"), "test3", newSet(" ltw "))));

        assertThat(
                indexNameExpressionResolver.resolveSearchRouting(state, "0,1,2,tw , ltw , lw", (String[])null),
                equalTo(newMap(
                        "test1", newSet("0", "1", "2", "tw ", " ltw ", " lw"),
                        "test2", newSet("0", "1", "2", "tw ", " ltw ", " lw"),
                        "test3", newSet("0", "1", "2", "tw ", " ltw ", " lw"))));

        assertThat(
                indexNameExpressionResolver.resolveSearchRoutingAllIndices(state.metadata(), "0,1,2,tw , ltw , lw"),
                equalTo(newMap(
                        "test1", newSet("0", "1", "2", "tw ", " ltw ", " lw"),
                        "test2", newSet("0", "1", "2", "tw ", " ltw ", " lw"),
                        "test3", newSet("0", "1", "2", "tw ", " ltw ", " lw"))));
    }

    private <T> Set<T> newSet(T... elements) {
        return newHashSet(elements);
    }

    private <K, V> Map<K, V> newMap(K key, V value) {
        Map<K, V> r = new HashMap<>();
        r.put(key, value);
        return r;
    }

    private <K, V> Map<K, V> newMap(K key1, V value1, K key2, V value2) {
        Map<K, V> r = new HashMap<>();
        r.put(key1, value1);
        r.put(key2, value2);
        return r;
    }

    private <K, V> Map<K, V> newMap(K key1, V value1, K key2, V value2, K key3, V value3) {
        Map<K, V> r = new HashMap<>();
        r.put(key1, value1);
        r.put(key2, value2);
        r.put(key3, value3);
        return r;
    }

}
