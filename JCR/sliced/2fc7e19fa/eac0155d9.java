/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.query.lucene;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.HitCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Searcher;
import org.apache.lucene.search.Similarity;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.MatchAllDocsQuery;

import java.io.IOException;
import java.util.BitSet;
import java.util.Set;

/**
 * Implements a lucene <code>Query</code> which filters a sub query by checking
 * whether the nodes selected by that sub query are descendants or self of
 * nodes selected by a context query.
 */
class DescendantSelfAxisQuery extends Query {

    /**
     * The context query
     */
    private final Query contextQuery;

    /**
     * The scorer of the context query
     */
    private Scorer contextScorer;

    /**
     * The sub query to filter
     */
    private final Query subQuery;

    /**
     * The minimal levels required between context and sub nodes for a sub node
     * to match.
     */
    private final int minLevels;

    /**
     * The scorer of the sub query to filter
     */
    private Scorer subScorer;

    /**
     * Creates a new <code>DescendantSelfAxisQuery</code> based on a
     * <code>context</code> and matches all descendants of the context nodes.
     * Whether the context nodes match as well is controlled by
     * <code>includeSelf</code>.
     *
     * @param context     the context for this query.
     * @param includeSelf if <code>true</code> this query acts like a
     *                    descendant-or-self axis. If <code>false</code> this
     *                    query acts like a descendant axis.
     */
    public DescendantSelfAxisQuery(Query context, boolean includeSelf) {
        this(context, new MatchAllDocsQuery(), includeSelf);
    }

    /**
     * Creates a new <code>DescendantSelfAxisQuery</code> based on a
     * <code>context</code> query and filtering the <code>sub</code> query.
     *
     * @param context the context for this query.
     * @param sub     the sub query.
     */
    public DescendantSelfAxisQuery(Query context, Query sub) {
        this(context, sub, true);
    }

    /**
     * Creates a new <code>DescendantSelfAxisQuery</code> based on a
     * <code>context</code> query and filtering the <code>sub</code> query.
     *
     * @param context     the context for this query.
     * @param sub         the sub query.
     * @param includeSelf if <code>true</code> this query acts like a
     *                    descendant-or-self axis. If <code>false</code> this query acts like
     *                    a descendant axis.
     */
    public DescendantSelfAxisQuery(Query context, Query sub, boolean includeSelf) {
        this(context, sub, includeSelf ? 0 : 1);
    }

    /**
     * Creates a new <code>DescendantSelfAxisQuery</code> based on a
     * <code>context</code> query and filtering the <code>sub</code> query.
     *
     * @param context   the context for this query.
     * @param sub       the sub query.
     * @param minLevels the minimal levels required between context and sub
     *                  nodes for a sub node to match.
     */
    public DescendantSelfAxisQuery(Query context, Query sub, int minLevels) {
        this.contextQuery = context;
        this.subQuery = sub;
        this.minLevels = minLevels;
    }

    /**
     * @return the context query of this <code>DescendantSelfAxisQuery</code>.
     */
    Query getContextQuery() {
        return contextQuery;
    }

    /**
     * @return <code>true</code> if the sub query of this <code>DescendantSelfAxisQuery</code>
     *         matches all nodes.
     */
    boolean subQueryMatchesAll() {
        return subQuery instanceof MatchAllDocsQuery;
    }

    /**
     * @return the minimal levels required between context and sub nodes for a
     *         sub node to match.
     */
    int getMinLevels() {
        return minLevels;
    }

    /**
     * Creates a <code>Weight</code> instance for this query.
     *
     * @param searcher the <code>Searcher</code> instance to use.
     * @return a <code>DescendantSelfAxisWeight</code>.
     */
    protected Weight createWeight(Searcher searcher) {
        return new DescendantSelfAxisWeight(searcher);
    }

    /**
     * Always returns 'DescendantSelfAxisQuery'.
     *
     * @param field the name of a field.
     * @return 'DescendantSelfAxisQuery'.
     */
    public String toString(String field) {
        return "DescendantSelfAxisQuery";
    }

    /**
     * {@inheritDoc}
     */
    public void extractTerms(Set terms) {
        contextQuery.extractTerms(terms);
        subQuery.extractTerms(terms);
    }

    /**
     * {@inheritDoc}
     */
    public Query rewrite(IndexReader reader) throws IOException {
        Query cQuery = contextQuery.rewrite(reader);
        Query sQuery = subQuery.rewrite(reader);
        if (contextQuery instanceof DescendantSelfAxisQuery) {
            DescendantSelfAxisQuery dsaq = (DescendantSelfAxisQuery) contextQuery;
            if (dsaq.subQueryMatchesAll()) {
                return new DescendantSelfAxisQuery(dsaq.getContextQuery(),
                        sQuery, dsaq.getMinLevels() + getMinLevels()).rewrite(reader);
            }
        }
        if (cQuery == contextQuery && sQuery == subQuery) {
            return this;
        } else {
            return new DescendantSelfAxisQuery(cQuery, sQuery, minLevels);
        }
    }

    //------------------------< DescendantSelfAxisWeight >--------------------------

    /**
     * The <code>Weight</code> implementation for this
     * <code>DescendantSelfAxisWeight</code>.
     */
    private class DescendantSelfAxisWeight implements Weight {

        /**
         * The searcher in use
         */
        private final Searcher searcher;

        /**
         * Creates a new <code>DescendantSelfAxisWeight</code> instance using
         * <code>searcher</code>.
         *
         * @param searcher a <code>Searcher</code> instance.
         */
        private DescendantSelfAxisWeight(Searcher searcher) {
            this.searcher = searcher;
        }

        //-----------------------------< Weight >-------------------------------

        /**
         * Returns this <code>DescendantSelfAxisQuery</code>.
         *
         * @return this <code>DescendantSelfAxisQuery</code>.
         */
        public Query getQuery() {
            return DescendantSelfAxisQuery.this;
        }

        /**
         * {@inheritDoc}
         */
        public float getValue() {
            return 1.0f;
        }

        /**
         * {@inheritDoc}
         */
        public float sumOfSquaredWeights() throws IOException {
            return 1.0f;
        }

        /**
         * {@inheritDoc}
         */
        public void normalize(float norm) {
        }

        /**
         * Creates a scorer for this <code>DescendantSelfAxisScorer</code>.
         *
         * @param reader a reader for accessing the index.
         * @return a <code>DescendantSelfAxisScorer</code>.
         * @throws IOException if an error occurs while reading from the index.
         */
        public Scorer scorer(IndexReader reader) throws IOException {
            contextScorer = contextQuery.weight(searcher).scorer(reader);
            subScorer = subQuery.weight(searcher).scorer(reader);
            HierarchyResolver resolver = (HierarchyResolver) reader;
            return new DescendantSelfAxisScorer(searcher.getSimilarity(), reader, resolver);
        }

        /**
         * {@inheritDoc}
         */
        public Explanation explain(IndexReader reader, int doc) throws IOException {
            return new Explanation();
        }
    }

    //----------------------< DescendantSelfAxisScorer >---------------------------------
    /**
     * Implements a <code>Scorer</code> for this
     * <code>DescendantSelfAxisQuery</code>.
     */
    private class DescendantSelfAxisScorer extends Scorer {

        /**
         * The <code>HierarchyResolver</code> of the index.
         */
        private final HierarchyResolver hResolver;

        /**
         * BitSet storing the id's of selected documents
         */
        private final BitSet contextHits;

        /**
         * Set <code>true</code> once the context hits have been calculated.
         */
        private boolean contextHitsCalculated = false;

        /**
         * Remember document numbers of ancestors during validation
         */
        private int[] ancestorDocs = new int[2];

        /**
         * Creates a new <code>DescendantSelfAxisScorer</code>.
         *
         * @param similarity the <code>Similarity</code> instance to use.
         * @param reader     for index access.
         * @param hResolver  the hierarchy resolver of <code>reader</code>.
         */
        protected DescendantSelfAxisScorer(Similarity similarity,
                                           IndexReader reader,
                                           HierarchyResolver hResolver) {
            super(similarity);
            this.hResolver = hResolver;
            // todo reuse BitSets?
            this.contextHits = new BitSet(reader.maxDoc());
        }

        /**
         * {@inheritDoc}
         */
        public boolean next() throws IOException {
            collectContextHits();
            if (!subScorer.next() || contextHits.isEmpty()) {
                return false;
            }
            int nextDoc = subScorer.doc();
            while (nextDoc > -1) {

                if (isValid(nextDoc)) {
                    return true;
                }

                // try next
                nextDoc = subScorer.next() ? subScorer.doc() : -1;
            }
            return false;
        }

        /**
         * {@inheritDoc}
         */
        public int doc() {
            return subScorer.doc();
        }

        /**
         * {@inheritDoc}
         */
        public float score() throws IOException {
            return subScorer.score();
        }

        /**
         * {@inheritDoc}
         */
        public boolean skipTo(int target) throws IOException {
            boolean match = subScorer.skipTo(target);
            if (match) {
                collectContextHits();
                if (isValid(subScorer.doc())) {
                    return true;
                } else {
                    // find next valid
                    return next();
                }
            } else {
                return true;
            }
        }

        private void collectContextHits() throws IOException {
            if (!contextHitsCalculated) {
                contextScorer.score(new HitCollector() {
                    public void collect(int doc, float score) {
                        contextHits.set(doc);
                    }
                }); // find all
                contextHitsCalculated = true;
            }
        }

        /**
         * @throws UnsupportedOperationException this implementation always
         *                                       throws an <code>UnsupportedOperationException</code>.
         */
        public Explanation explain(int doc) throws IOException {
            throw new UnsupportedOperationException();
        }

        /**
         * Returns <code>true</code> if <code>doc</code> is a valid match from
         * the sub scorer against the context hits. The caller must ensure
         * that the context hits are calculated before this method is called!
         *
         * @param doc the document number.
         * @return <code>true</code> if <code>doc</code> is valid.
         * @throws IOException if an error occurs while reading from the index.
         */
        private boolean isValid(int doc) throws IOException {
            // check self if necessary
            if (minLevels == 0 && contextHits.get(doc)) {
                return true;
            }

            // check if doc is a descendant of one of the context nodes
            int parentDoc = hResolver.getParent(doc);

            int ancestorCount = 0;
            ancestorDocs[ancestorCount++] = parentDoc;

            // traverse
            while (parentDoc != -1 && (!contextHits.get(parentDoc) || ancestorCount < minLevels)) {
                parentDoc = hResolver.getParent(parentDoc);
                // resize array if needed
                if (ancestorCount == ancestorDocs.length) {
                    // double the size of the new array
                    int[] copy = new int[ancestorDocs.length * 2];
                    System.arraycopy(ancestorDocs, 0, copy, 0, ancestorDocs.length);
                    ancestorDocs = copy;
                }
                ancestorDocs[ancestorCount++] = parentDoc;
            }
            if (parentDoc != -1) {
                // since current parentDoc is a descendant of one of the context
                // docs we can promote all ancestorDocs to the context hits
                for (int i = 0; i < ancestorCount; i++) {
                    contextHits.set(ancestorDocs[i]);
                }
                return true;
            }
            return false;
        }
    }
}
