/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.highlight;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import javax.xml.xpath.XPathConstants;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CachingTokenFilter;
import org.apache.lucene.analysis.Token;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.highlight.Formatter;
import org.apache.lucene.search.highlight.Fragmenter;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SpanScorer;
import org.apache.lucene.search.highlight.TextFragment;
import org.apache.lucene.search.highlight.TokenSources;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.Config;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.DocList;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.plugin.NamedListPluginLoader;
import org.w3c.dom.NodeList;

/**
 * 
 * @since solr 1.3
 */
public class DefaultSolrHighlighter extends SolrHighlighter
{
  
  public void initalize( final Config config )
  {
    formatters.clear();
    fragmenters.clear();
    
    // Load the fragmenters
    String xpath = "highlighting/fragmenter";
    NamedListPluginLoader<SolrFragmenter> fragloader = new NamedListPluginLoader<SolrFragmenter>( xpath, fragmenters );
    SolrFragmenter frag = fragloader.load( config.getResourceLoader(), (NodeList)config.evaluate( xpath, XPathConstants.NODESET ) );
    if( frag == null ) {
      frag = new GapFragmenter();
    }
    fragmenters.put( "", frag );
    fragmenters.put( null, frag );
    
    // Load the formatters
    xpath = "highlighting/formatter";
    NamedListPluginLoader<SolrFormatter> fmtloader = new NamedListPluginLoader<SolrFormatter>( xpath, formatters );
    SolrFormatter fmt = fmtloader.load( config.getResourceLoader(), (NodeList)config.evaluate( xpath, XPathConstants.NODESET ) );
    if( fmt == null ) {
      fmt = new HtmlFormatter();
    }
    formatters.put( "", fmt );
    formatters.put( null, fmt );
  }
  
  /**
   * Return a phrase Highlighter appropriate for this field.
   * @param query The current Query
   * @param fieldName The name of the field
   * @param request The current SolrQueryRequest
   * @param tokenStream document text CachingTokenStream
   * @throws IOException 
   */
  protected Highlighter getPhraseHighlighter(Query query, String fieldName, SolrQueryRequest request, CachingTokenFilter tokenStream) throws IOException {
    SolrParams params = request.getParams();
    Highlighter highlighter = null;
    
    highlighter = new Highlighter(getFormatter(fieldName, params), getSpanQueryScorer(query, fieldName, tokenStream, request));
    
    highlighter.setTextFragmenter(getFragmenter(fieldName, params));

    return highlighter;
  }
  
  /**
   * Return a Highlighter appropriate for this field.
   * @param query The current Query
   * @param fieldName The name of the field
   * @param request The current SolrQueryRequest
   */
  protected Highlighter getHighlighter(Query query, String fieldName, SolrQueryRequest request) {
    SolrParams params = request.getParams(); 
    Highlighter highlighter = new Highlighter(
           getFormatter(fieldName, params), 
           getQueryScorer(query, fieldName, request));
     highlighter.setTextFragmenter(getFragmenter(fieldName, params));
       return highlighter;
  }
  
  /**
   * Return a SpanScorer suitable for this Query and field.
   * @param query The current query
   * @param tokenStream document text CachingTokenStream
   * @param fieldName The name of the field
   * @param request The SolrQueryRequest
   * @throws IOException 
   */
  private SpanScorer getSpanQueryScorer(Query query, String fieldName, CachingTokenFilter tokenStream, SolrQueryRequest request) throws IOException {
    boolean reqFieldMatch = request.getParams().getFieldBool(fieldName, HighlightParams.FIELD_MATCH, false);
    if (reqFieldMatch) {
      return new SpanScorer(query, fieldName, tokenStream);
    }
    else {
      return new SpanScorer(query, null, tokenStream);
    }
  }

  /**
   * Return a QueryScorer suitable for this Query and field.
   * @param query The current query
   * @param fieldName The name of the field
   * @param request The SolrQueryRequest
   */
  protected QueryScorer getQueryScorer(Query query, String fieldName, SolrQueryRequest request) {
     boolean reqFieldMatch = request.getParams().getFieldBool(fieldName, HighlightParams.FIELD_MATCH, false);
     if (reqFieldMatch) {
        return new QueryScorer(query, request.getSearcher().getReader(), fieldName);
     }
     else {
        return new QueryScorer(query);
     }
  }
  
  /**
   * Return the max number of snippets for this field. If this has not
   * been configured for this field, fall back to the configured default
   * or the solr default.
   * @param fieldName The name of the field
   * @param params The params controlling Highlighting
   */
  protected int getMaxSnippets(String fieldName, SolrParams params) {
     return params.getFieldInt(fieldName, HighlightParams.SNIPPETS,1);
  }

  /**
   * Return whether adjacent fragments should be merged.
   * @param fieldName The name of the field
   * @param params The params controlling Highlighting
   */
  protected boolean isMergeContiguousFragments(String fieldName, SolrParams params){
    return params.getFieldBool(fieldName, HighlightParams.MERGE_CONTIGUOUS_FRAGMENTS, false);
  }
  
  /**
   * Return a formatter appropriate for this field. If a formatter
   * has not been configured for this field, fall back to the configured
   * default or the solr default (SimpleHTMLFormatter).
   * 
   * @param fieldName The name of the field
   * @param params The params controlling Highlighting
   * @return An appropriate Formatter.
   */
  protected Formatter getFormatter(String fieldName, SolrParams params ) 
  {
    String str = params.getFieldParam( fieldName, HighlightParams.FORMATTER );
    SolrFormatter formatter = formatters.get( str );
    if( formatter == null ) {
      throw new SolrException( SolrException.ErrorCode.BAD_REQUEST, "Unknown formatter: "+str );
    }
    return formatter.getFormatter( fieldName, params );
  }
  
  /**
   * Return a fragmenter appropriate for this field. If a fragmenter
   * has not been configured for this field, fall back to the configured
   * default or the solr default (GapFragmenter).
   * 
   * @param fieldName The name of the field
   * @param params The params controlling Highlighting
   * @return An appropriate Fragmenter.
   */
  protected Fragmenter getFragmenter(String fieldName, SolrParams params) 
  {
    String fmt = params.getFieldParam( fieldName, HighlightParams.FRAGMENTER );
    SolrFragmenter frag = fragmenters.get( fmt );
    if( frag == null ) {
      throw new SolrException( SolrException.ErrorCode.BAD_REQUEST, "Unknown fragmenter: "+fmt );
    }
    return frag.getFragmenter( fieldName, params );
  }
  
  /**
   * Generates a list of Highlighted query fragments for each item in a list
   * of documents, or returns null if highlighting is disabled.
   *
   * @param docs query results
   * @param query the query
   * @param req the current request
   * @param defaultFields default list of fields to summarize
   *
   * @return NamedList containing a NamedList for each document, which in 
   * turns contains sets (field, summary) pairs.
   */
  @SuppressWarnings("unchecked")
  public NamedList<Object> doHighlighting(DocList docs, Query query, SolrQueryRequest req, String[] defaultFields) throws IOException {
    SolrParams params = req.getParams(); 
    if (!isHighlightingEnabled(params))
        return null;
     
     SolrIndexSearcher searcher = req.getSearcher();
     IndexSchema schema = searcher.getSchema();
     NamedList fragments = new SimpleOrderedMap();
     String[] fieldNames = getHighlightFields(query, req, defaultFields);
     Document[] readDocs = new Document[docs.size()];
     {
       // pre-fetch documents using the Searcher's doc cache
       Set<String> fset = new HashSet<String>();
       for(String f : fieldNames) { fset.add(f); }
       // fetch unique key if one exists.
       SchemaField keyField = schema.getUniqueKeyField();
       if(null != keyField)
         fset.add(keyField.getName());  
       searcher.readDocs(readDocs, docs, fset);
     }


    // Highlight each document
    DocIterator iterator = docs.iterator();
    for (int i = 0; i < docs.size(); i++) {
       int docId = iterator.nextDoc();
       Document doc = readDocs[i];
       NamedList docSummaries = new SimpleOrderedMap();
       for (String fieldName : fieldNames) {
          fieldName = fieldName.trim();
          String[] docTexts = doc.getValues(fieldName);
          if (docTexts == null) continue;
          
          TokenStream tstream = null;
          int numFragments = getMaxSnippets(fieldName, params);
          boolean mergeContiguousFragments = isMergeContiguousFragments(fieldName, params);

          String[] summaries = null;
          List<TextFragment> frags = new ArrayList<TextFragment>();
          TermOffsetsTokenStream tots = null;
          for (int j = 0; j < docTexts.length; j++) {
            // create TokenStream
            try {
              // attempt term vectors
              if( tots == null )
                tots = new TermOffsetsTokenStream( TokenSources.getTokenStream(searcher.getReader(), docId, fieldName) );
              tstream = tots.getMultiValuedTokenStream( docTexts[j].length() );
            }
            catch (IllegalArgumentException e) {
              // fall back to anaylzer
              tstream = new TokenOrderingFilter(schema.getAnalyzer().tokenStream(fieldName, new StringReader(docTexts[j])), 10);
            }
             
            Highlighter highlighter;
            if (Boolean.valueOf(req.getParams().get(HighlightParams.USE_PHRASE_HIGHLIGHTER))) {
              // wrap CachingTokenFilter around TokenStream for reuse
              tstream = new CachingTokenFilter(tstream);
              
              // get highlighter
              highlighter = getPhraseHighlighter(query, fieldName, req, (CachingTokenFilter) tstream);
               
              // after highlighter initialization, reset tstream since construction of highlighter already used it
              tstream.reset();
            }
            else {
              // use "the old way"
              highlighter = getHighlighter(query, fieldName, req);
            }
            
            int maxCharsToAnalyze = params.getFieldInt(fieldName,
                HighlightParams.MAX_CHARS,
                Highlighter.DEFAULT_MAX_CHARS_TO_ANALYZE);
            if (maxCharsToAnalyze < 0) {
              highlighter.setMaxDocCharsToAnalyze(docTexts[j].length());
            } else {
              highlighter.setMaxDocCharsToAnalyze(maxCharsToAnalyze);
            }
            
            TextFragment[] bestTextFragments = highlighter.getBestTextFragments(tstream, docTexts[j], mergeContiguousFragments, numFragments);
            for (int k = 0; k < bestTextFragments.length; k++) {
              if ((bestTextFragments[k] != null) && (bestTextFragments[k].getScore() > 0)) {
                frags.add(bestTextFragments[k]);
              }
            }
          }
          // sort such that the fragments with the highest score come first
          Collections.sort(frags, new Comparator<TextFragment>() {
            public int compare(TextFragment arg0, TextFragment arg1) {
              return Math.round(arg1.getScore() - arg0.getScore());
            }
          });
          
           // convert fragments back into text
           // TODO: we can include score and position information in output as snippet attributes
          if (frags.size() > 0) {
            ArrayList<String> fragTexts = new ArrayList<String>();
            for (TextFragment fragment: frags) {
              if ((fragment != null) && (fragment.getScore() > 0)) {
                fragTexts.add(fragment.toString());
              }
              if (fragTexts.size() >= numFragments) break;
            }
            summaries = fragTexts.toArray(new String[0]);
            if (summaries.length > 0) 
            docSummaries.add(fieldName, summaries);
          }
           // no summeries made, copy text from alternate field
           if (summaries == null || summaries.length == 0) {
              String alternateField = req.getParams().getFieldParam(fieldName, HighlightParams.ALTERNATE_FIELD);
              if (alternateField != null && alternateField.length() > 0) {
                String[] altTexts = doc.getValues(alternateField);
                if (altTexts != null && altTexts.length > 0){
                  int alternateFieldLen = req.getParams().getFieldInt(fieldName, HighlightParams.ALTERNATE_FIELD_LENGTH,0);
                  if( alternateFieldLen <= 0 ){
                    docSummaries.add(fieldName, altTexts);
                  }
                  else{
                    List<String> altList = new ArrayList<String>();
                    int len = 0;
                    for( String altText: altTexts ){
                      altList.add( len + altText.length() > alternateFieldLen ?
                                   altText.substring( 0, alternateFieldLen - len ) : altText );
                      len += altText.length();
                      if( len >= alternateFieldLen ) break;
                    }
                    docSummaries.add(fieldName, altList);
                  }
                }
              }
           }
 
        }
        String printId = schema.printableUniqueKey(doc);
        fragments.add(printId == null ? null : printId, docSummaries);
     }
     return fragments;
  }
}

/** Orders Tokens in a window first by their startOffset ascending.
 * endOffset is currently ignored.
 * This is meant to work around fickleness in the highlighter only.  It
 * can mess up token positions and should not be used for indexing or querying.
 */
class TokenOrderingFilter extends TokenFilter {
  private final int windowSize;
  private final LinkedList<Token> queue = new LinkedList<Token>();
  private boolean done=false;

  protected TokenOrderingFilter(TokenStream input, int windowSize) {
    super(input);
    this.windowSize = windowSize;
  }

  @Override
  public Token next() throws IOException {
    while (!done && queue.size() < windowSize) {
      Token newTok = input.next();
      if (newTok==null) {
        done=true;
        break;
      }

      // reverse iterating for better efficiency since we know the
      // list is already sorted, and most token start offsets will be too.
      ListIterator<Token> iter = queue.listIterator(queue.size());
      while(iter.hasPrevious()) {
        if (newTok.startOffset() >= iter.previous().startOffset()) {
          // insertion will be before what next() would return (what
          // we just compared against), so move back one so the insertion
          // will be after.
          iter.next();
          break;
        }
      }
      iter.add(newTok);
    }

    return queue.isEmpty() ? null : queue.removeFirst();
  }
}

class TermOffsetsTokenStream {

  TokenStream bufferedTokenStream = null;
  Token bufferedToken;
  int startOffset;
  int endOffset;

  public TermOffsetsTokenStream( TokenStream tstream ){
    bufferedTokenStream = tstream;
    startOffset = 0;
    bufferedToken = null;
  }

  public TokenStream getMultiValuedTokenStream( final int length ){
    endOffset = startOffset + length;
    return new TokenStream(){
      Token token;
      public Token next() throws IOException {
        while( true ){
          if( bufferedToken == null )
            bufferedToken = bufferedTokenStream.next();
          if( bufferedToken == null ) return null;
          if( startOffset <= bufferedToken.startOffset() &&
              bufferedToken.endOffset() <= endOffset ){
            token = bufferedToken;
            bufferedToken = null;
            token.setStartOffset( token.startOffset() - startOffset );
            token.setEndOffset( token.endOffset() - startOffset );
            return token;
          }
          else if( bufferedToken.endOffset() > endOffset ){
            startOffset += length + 1;
            return null;
          }
          bufferedToken = null;
        }
      }
    };
  }
}