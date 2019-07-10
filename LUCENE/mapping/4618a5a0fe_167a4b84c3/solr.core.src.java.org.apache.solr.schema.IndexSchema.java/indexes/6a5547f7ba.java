/*
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

package org.apache.solr.schema;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.AnalyzerWrapper;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.StorableField;
import org.apache.lucene.index.StoredDocument;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.util.Version;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.util.DOMUtil;
import org.apache.solr.util.SystemIdResolver;
import org.apache.solr.core.SolrConfig;
import org.apache.solr.core.Config;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.search.similarities.DefaultSimilarityFactory;
import org.apache.solr.util.plugin.SolrCoreAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * <code>IndexSchema</code> contains information about the valid fields in an index
 * and the types of those fields.
 *
 *
 */
public final class IndexSchema {
  public static final String COPY_FIELD = "copyField";
  public static final String COPY_FIELDS = COPY_FIELD + "s";
  public static final String DEFAULT_OPERATOR = "defaultOperator";
  public static final String DEFAULT_SCHEMA_FILE = "schema.xml";
  public static final String DEFAULT_SEARCH_FIELD = "defaultSearchField";
  public static final String DESTINATION = "dest";
  public static final String DYNAMIC_FIELD = "dynamicField";
  public static final String DYNAMIC_FIELDS = DYNAMIC_FIELD + "s";
  public static final String FIELD = "field";
  public static final String FIELDS = FIELD + "s";
  public static final String FIELD_TYPE = "fieldType";
  public static final String FIELD_TYPES = FIELD_TYPE + "s";
  public static final String INTERNAL_POLY_FIELD_PREFIX = "*" + FieldType.POLY_FIELD_SEPARATOR;
  public static final String LUCENE_MATCH_VERSION_PARAM = "luceneMatchVersion";
  public static final String NAME = "name";
  public static final String REQUIRED = "required";
  public static final String SCHEMA = "schema";
  public static final String SIMILARITY = "similarity";
  public static final String SOLR_QUERY_PARSER = "solrQueryParser";
  public static final String SOURCE = "source";
  public static final String TYPES = "types";
  public static final String UNIQUE_KEY = "uniqueKey";
  public static final String VERSION = "version";

  private static final String AT = "@";
  private static final String DESTINATION_DYNAMIC_BASE = "destDynamicBase";
  private static final String MAX_CHARS = "maxChars";
  private static final String SLASH = "/";
  private static final String SOURCE_DYNAMIC_BASE = "sourceDynamicBase";
  private static final String SOURCE_EXPLICIT_FIELDS = "sourceExplicitFields";
  private static final String TEXT_FUNCTION = "text()";
  private static final String TYPE = "type";
  private static final String XPATH_OR = " | ";

  final static Logger log = LoggerFactory.getLogger(IndexSchema.class);
  private final SolrConfig solrConfig;
  private final String resourceName;
  private String name;
  private float version;
  private final SolrResourceLoader loader;

  private final HashMap<String, SchemaField> fields = new HashMap<String,SchemaField>();


  private final HashMap<String, FieldType> fieldTypes = new HashMap<String,FieldType>();

  private final List<SchemaField> fieldsWithDefaultValue = new ArrayList<SchemaField>();
  private final Collection<SchemaField> requiredFields = new HashSet<SchemaField>();
  private DynamicField[] dynamicFields;
  public DynamicField[] getDynamicFields() { return dynamicFields; }

  private Analyzer analyzer;
  private Analyzer queryAnalyzer;

  private String defaultSearchFieldName=null;
  private String queryParserDefaultOperator = "OR";
  private boolean isExplicitQueryParserDefaultOperator = false;


  private final Map<String, List<CopyField>> copyFieldsMap = new HashMap<String, List<CopyField>>();
  public Map<String,List<CopyField>> getCopyFieldsMap() { return Collections.unmodifiableMap(copyFieldsMap); }
  
  private DynamicCopy[] dynamicCopyFields;
  public DynamicCopy[] getDynamicCopyFields() { return dynamicCopyFields; }

  /**
   * keys are all fields copied to, count is num of copyField
   * directives that target them.
   */
  private Map<SchemaField, Integer> copyFieldTargetCounts = new HashMap<SchemaField, Integer>();

    /**
   * Constructs a schema using the specified resource name and stream.
   * If the is stream is null, the resource loader will load the schema resource by name.
   * @see SolrResourceLoader#openSchema
   * By default, this follows the normal config path directory searching rules.
   * @see SolrResourceLoader#openResource
   */
  public IndexSchema(SolrConfig solrConfig, String name, InputSource is) {
    this.solrConfig = solrConfig;
    if (name == null)
      name = DEFAULT_SCHEMA_FILE;
    this.resourceName = name;
    loader = solrConfig.getResourceLoader();
    try {
      if (is == null) {
        is = new InputSource(loader.openSchema(name));
        is.setSystemId(SystemIdResolver.createSystemIdFromResourceName(name));
      }
      readSchema(is);
      loader.inform( loader );
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
  
  /**
   * @since solr 1.4
   */
  public SolrResourceLoader getResourceLoader() {
    return loader;
  }
  
  /** Gets the name of the resource used to instantiate this schema. */
  public String getResourceName() {
    return resourceName;
  }
  
  /** Gets the name of the schema as specified in the schema resource. */
  public String getSchemaName() {
    return name;
  }
  
  /** The Default Lucene Match Version for this IndexSchema */
  public Version getDefaultLuceneMatchVersion() {
    return solrConfig.luceneMatchVersion;
  }

  public float getVersion() {
    return version;
  }


  /**
   * Provides direct access to the Map containing all explicit
   * (ie: non-dynamic) fields in the index, keyed on field name.
   *
   * <p>
   * Modifying this Map (or any item in it) will affect the real schema
   * </p>
   * 
   * <p>
   * NOTE: this function is not thread safe.  However, it is safe to use within the standard
   * <code>inform( SolrCore core )</code> function for <code>SolrCoreAware</code> classes.
   * Outside <code>inform</code>, this could potentially throw a ConcurrentModificationException
   * </p>
   */
  public Map<String,SchemaField> getFields() { return fields; }

  /**
   * Provides direct access to the Map containing all Field Types
   * in the index, keyed on field type name.
   *
   * <p>
   * Modifying this Map (or any item in it) will affect the real schema.  However if you 
   * make any modifications, be sure to call {@link IndexSchema#refreshAnalyzers()} to
   * update the Analyzers for the registered fields.
   * </p>
   * 
   * <p>
   * NOTE: this function is not thread safe.  However, it is safe to use within the standard
   * <code>inform( SolrCore core )</code> function for <code>SolrCoreAware</code> classes.
   * Outside <code>inform</code>, this could potentially throw a ConcurrentModificationException
   * </p>
   */
  public Map<String,FieldType> getFieldTypes() { return fieldTypes; }

  /**
   * Provides direct access to the List containing all fields with a default value
   */
  public List<SchemaField> getFieldsWithDefaultValue() { return fieldsWithDefaultValue; }

  /**
   * Provides direct access to the List containing all required fields.  This
   * list contains all fields with default values.
   */
  public Collection<SchemaField> getRequiredFields() { return requiredFields; }

  private Similarity similarity;

  /**
   * Returns the Similarity used for this index
   */
  public Similarity getSimilarity() { return similarity; }

  private SimilarityFactory similarityFactory;
  private boolean isExplicitSimilarity = false;


  /** Returns the SimilarityFactory that constructed the Similarity for this index */
  public SimilarityFactory getSimilarityFactory() { return similarityFactory; }
  
  /**
   * Returns the Analyzer used when indexing documents for this index
   *
   * <p>
   * This Analyzer is field (and dynamic field) name aware, and delegates to
   * a field specific Analyzer based on the field type.
   * </p>
   */
  public Analyzer getAnalyzer() { return analyzer; }

  /**
   * Returns the Analyzer used when searching this index
   *
   * <p>
   * This Analyzer is field (and dynamic field) name aware, and delegates to
   * a field specific Analyzer based on the field type.
   * </p>
   */
  public Analyzer getQueryAnalyzer() { return queryAnalyzer; }

  
  /**
   * Name of the default search field specified in the schema file.
   * <br/><b>Note:</b>Avoid calling this, try to use this method so that the 'df' param is consulted as an override:
   * {@link org.apache.solr.search.QueryParsing#getDefaultField(IndexSchema, String)}
   */
  public String getDefaultSearchFieldName() {
    return defaultSearchFieldName;
  }

  /**
   * default operator ("AND" or "OR") for QueryParser
   */
  public String getQueryParserDefaultOperator() {
    return queryParserDefaultOperator;
  }

  private SchemaField uniqueKeyField;

  /**
   * Unique Key field specified in the schema file
   * @return null if this schema has no unique key field
   */
  public SchemaField getUniqueKeyField() { return uniqueKeyField; }

  private String uniqueKeyFieldName;
  private FieldType uniqueKeyFieldType;

  /**
   * The raw (field type encoded) value of the Unique Key field for
   * the specified Document
   * @return null if this schema has no unique key field
   * @see #printableUniqueKey
   */
  public IndexableField getUniqueKeyField(org.apache.lucene.document.Document doc) {
    return doc.getField(uniqueKeyFieldName);  // this should return null if name is null
  }

  /**
   * The printable value of the Unique Key field for
   * the specified Document
   * @return null if this schema has no unique key field
   */
  public String printableUniqueKey(StoredDocument doc) {
    StorableField f = doc.getField(uniqueKeyFieldName);
    return f==null ? null : uniqueKeyFieldType.toExternal(f);
  }

  private SchemaField getIndexedField(String fname) {
    SchemaField f = getFields().get(fname);
    if (f==null) {
      throw new RuntimeException("unknown field '" + fname + "'");
    }
    if (!f.indexed()) {
      throw new RuntimeException("'"+fname+"' is not an indexed field:" + f);
    }
    return f;
  }
  
  /**
   * This will re-create the Analyzers.  If you make any modifications to
   * the Field map ({@link IndexSchema#getFields()}, this function is required
   * to synch the internally cached field analyzers.
   * 
   * @since solr 1.3
   */
  public void refreshAnalyzers() {
    analyzer = new SolrIndexAnalyzer();
    queryAnalyzer = new SolrQueryAnalyzer();
  }

  private class SolrIndexAnalyzer extends AnalyzerWrapper {
    protected final HashMap<String, Analyzer> analyzers;

    SolrIndexAnalyzer() {
      analyzers = analyzerCache();
    }

    protected HashMap<String, Analyzer> analyzerCache() {
      HashMap<String, Analyzer> cache = new HashMap<String, Analyzer>();
      for (SchemaField f : getFields().values()) {
        Analyzer analyzer = f.getType().getAnalyzer();
        cache.put(f.getName(), analyzer);
      }
      return cache;
    }

    @Override
    protected Analyzer getWrappedAnalyzer(String fieldName) {
      Analyzer analyzer = analyzers.get(fieldName);
      return analyzer != null ? analyzer : getDynamicFieldType(fieldName).getAnalyzer();
    }

    @Override
    protected TokenStreamComponents wrapComponents(String fieldName, TokenStreamComponents components) {
      return components;
    }
  }

  private class SolrQueryAnalyzer extends SolrIndexAnalyzer {
    @Override
    protected HashMap<String, Analyzer> analyzerCache() {
      HashMap<String, Analyzer> cache = new HashMap<String, Analyzer>();
       for (SchemaField f : getFields().values()) {
        Analyzer analyzer = f.getType().getQueryAnalyzer();
        cache.put(f.getName(), analyzer);
      }
      return cache;
    }

    @Override
    protected Analyzer getWrappedAnalyzer(String fieldName) {
      Analyzer analyzer = analyzers.get(fieldName);
      return analyzer != null ? analyzer : getDynamicFieldType(fieldName).getQueryAnalyzer();
    }
  }

  private void readSchema(InputSource is) {
    log.info("Reading Solr Schema");

    try {
      // pass the config resource loader to avoid building an empty one for no reason:
      // in the current case though, the stream is valid so we wont load the resource by name
      Config schemaConf = new Config(loader, SCHEMA, is, SLASH+SCHEMA+SLASH);
      Document document = schemaConf.getDocument();
      final XPath xpath = schemaConf.getXPath();
      final List<SchemaAware> schemaAware = new ArrayList<SchemaAware>();
      String expression = stepsToPath(SCHEMA, AT + NAME);
      Node nd = (Node) xpath.evaluate(expression, document, XPathConstants.NODE);
      if (nd==null) {
        log.warn("schema has no name!");
      } else {
        name = nd.getNodeValue();
        log.info("Schema " + NAME + "=" + name);
      }

      //                      /schema/@version
      expression = stepsToPath(SCHEMA, AT + VERSION);
      version = schemaConf.getFloat(expression, 1.0f);


      // load the Field Types

      final FieldTypePluginLoader typeLoader 
        = new FieldTypePluginLoader(this, fieldTypes, schemaAware);

      //               /schema/types/fieldtype | /schema/types/fieldType 
      expression =     stepsToPath(SCHEMA, TYPES, FIELD_TYPE.toLowerCase(Locale.ROOT)) // backcompat(?) 
          + XPATH_OR + stepsToPath(SCHEMA, TYPES, FIELD_TYPE);
      NodeList nodes = (NodeList) xpath.evaluate(expression, document, XPathConstants.NODESET);
      typeLoader.load( loader, nodes );

      // load the Fields

      // Hang on to the fields that say if they are required -- this lets us set a reasonable default for the unique key
      Map<String,Boolean> explicitRequiredProp = new HashMap<String, Boolean>();
      ArrayList<DynamicField> dFields = new ArrayList<DynamicField>();

      //               /schema/fields/field | /schema/fields/dynamicField
      expression =     stepsToPath(SCHEMA, FIELDS, FIELD)
          + XPATH_OR + stepsToPath(SCHEMA, FIELDS, DYNAMIC_FIELD);
      nodes = (NodeList) xpath.evaluate(expression, document, XPathConstants.NODESET);

      for (int i=0; i<nodes.getLength(); i++) {
        Node node = nodes.item(i);

        NamedNodeMap attrs = node.getAttributes();

        String name = DOMUtil.getAttr(attrs, NAME, "field definition");
        log.trace("reading field def "+name);
        String type = DOMUtil.getAttr(attrs, TYPE, "field " + name);

        FieldType ft = fieldTypes.get(type);
        if (ft==null) {
          throw new SolrException
              (ErrorCode.BAD_REQUEST, "Unknown " + FIELD_TYPE + " '" + type + "' specified on field " + name);
        }

        Map<String,String> args = DOMUtil.toMapExcept(attrs, NAME, TYPE);
        if (null != args.get(REQUIRED)) {
          explicitRequiredProp.put(name, Boolean.valueOf(args.get(REQUIRED)));
        }

        SchemaField f = SchemaField.create(name,ft,args);

        if (node.getNodeName().equals(FIELD)) {
          SchemaField old = fields.put(f.getName(),f);
          if( old != null ) {
            String msg = "[schema.xml] Duplicate field definition for '"
              + f.getName() + "' [[["+old.toString()+"]]] and [[["+f.toString()+"]]]";
            throw new SolrException(ErrorCode.SERVER_ERROR, msg );
          }
          log.debug("field defined: " + f);
          if( f.getDefaultValue() != null ) {
            log.debug(name+" contains default value: " + f.getDefaultValue());
            fieldsWithDefaultValue.add( f );
          }
          if (f.isRequired()) {
            log.debug(name+" is required in this schema");
            requiredFields.add(f);
          }
        } else if (node.getNodeName().equals(DYNAMIC_FIELD)) {
          if (isValidFieldGlob(name)) {
            // make sure nothing else has the same path
            addDynamicField(dFields, f);
          } else {
            String msg = "Dynamic field name '" + name 
                + "' should have either a leading or a trailing asterisk, and no others.";
            throw new SolrException(ErrorCode.SERVER_ERROR, msg);
          }
        } else {
          // we should never get here
          throw new RuntimeException("Unknown field type");
        }
      }
      
      //fields with default values are by definition required
      //add them to required fields, and we only have to loop once
      // in DocumentBuilder.getDoc()
      requiredFields.addAll(getFieldsWithDefaultValue());


      // OK, now sort the dynamic fields largest to smallest size so we don't get
      // any false matches.  We want to act like a compiler tool and try and match
      // the largest string possible.
      Collections.sort(dFields);

      log.trace("Dynamic Field Ordering:" + dFields);

      // stuff it in a normal array for faster access
      dynamicFields = dFields.toArray(new DynamicField[dFields.size()]);

      expression = stepsToPath(SCHEMA, SIMILARITY); //   /schema/similarity
      Node node = (Node) xpath.evaluate(expression, document, XPathConstants.NODE);
      similarityFactory = readSimilarity(loader, node);
      if (similarityFactory == null) {
        similarityFactory = new DefaultSimilarityFactory();
      } else {
        isExplicitSimilarity = true;
      }
      if (similarityFactory instanceof SchemaAware) {
        ((SchemaAware)similarityFactory).inform(this);
      } else {
        // if the sim factory isn't schema aware, then we are responsible for
        // erroring if a field type is trying to specify a sim.
        for (FieldType ft : fieldTypes.values()) {
          if (null != ft.getSimilarity()) {
            String msg = "FieldType '" + ft.getTypeName()
                + "' is configured with a similarity, but the global similarity does not support it: " 
                + similarityFactory.getClass();
            log.error(msg);
            throw new SolrException(ErrorCode.SERVER_ERROR, msg);
          }
        }
      }
      similarity = similarityFactory.getSimilarity();

      //                      /schema/defaultSearchField/@text()
      expression = stepsToPath(SCHEMA, DEFAULT_SEARCH_FIELD, TEXT_FUNCTION);
      node = (Node) xpath.evaluate(expression, document, XPathConstants.NODE);
      if (node==null) {
        log.debug("no default search field specified in schema.");
      } else {
        defaultSearchFieldName=node.getNodeValue().trim();
        // throw exception if specified, but not found or not indexed
        if (defaultSearchFieldName!=null) {
          SchemaField defaultSearchField = getFields().get(defaultSearchFieldName);
          if ((defaultSearchField == null) || !defaultSearchField.indexed()) {
            String msg =  "default search field '" + defaultSearchFieldName + "' not defined or not indexed" ;
            throw new SolrException(ErrorCode.SERVER_ERROR, msg);
          }
        }
        log.info("default search field in schema is "+defaultSearchFieldName);
      }

      //                      /schema/solrQueryParser/@defaultOperator
      expression = stepsToPath(SCHEMA, SOLR_QUERY_PARSER, AT + DEFAULT_OPERATOR);
      node = (Node) xpath.evaluate(expression, document, XPathConstants.NODE);
      if (node==null) {
        log.debug("using default query parser operator (OR)");
      } else {
        isExplicitQueryParserDefaultOperator = true;
        queryParserDefaultOperator=node.getNodeValue().trim();
        log.info("query parser default operator is "+queryParserDefaultOperator);
      }

      //                      /schema/uniqueKey/text()
      expression = stepsToPath(SCHEMA, UNIQUE_KEY, TEXT_FUNCTION);
      node = (Node) xpath.evaluate(expression, document, XPathConstants.NODE);
      if (node==null) {
        log.warn("no " + UNIQUE_KEY + " specified in schema.");
      } else {
        uniqueKeyField=getIndexedField(node.getNodeValue().trim());
        if (null != uniqueKeyField.getDefaultValue()) {
          String msg = UNIQUE_KEY + " field ("+uniqueKeyFieldName+
              ") can not be configured with a default value ("+
              uniqueKeyField.getDefaultValue()+")";
          log.error(msg);
          throw new SolrException(ErrorCode.SERVER_ERROR, msg);
        }

        if (!uniqueKeyField.stored()) {
          log.warn(UNIQUE_KEY + " is not stored - distributed search and MoreLikeThis will not work");
        }
        if (uniqueKeyField.multiValued()) {
          String msg = UNIQUE_KEY + " field ("+uniqueKeyFieldName+
              ") can not be configured to be multivalued";
          log.error(msg);
          throw new SolrException(ErrorCode.SERVER_ERROR, msg);
        }
        uniqueKeyFieldName=uniqueKeyField.getName();
        uniqueKeyFieldType=uniqueKeyField.getType();
        log.info("unique key field: "+uniqueKeyFieldName);
      
        // Unless the uniqueKeyField is marked 'required=false' then make sure it exists
        if( Boolean.FALSE != explicitRequiredProp.get( uniqueKeyFieldName ) ) {
          uniqueKeyField.required = true;
          requiredFields.add(uniqueKeyField);
        }
      }                

      /////////////// parse out copyField commands ///////////////
      // Map<String,ArrayList<SchemaField>> cfields = new HashMap<String,ArrayList<SchemaField>>();
      // expression = "/schema/copyField";
    
      dynamicCopyFields = new DynamicCopy[] {};
      expression = "//" + COPY_FIELD;
      nodes = (NodeList) xpath.evaluate(expression, document, XPathConstants.NODESET);

      for (int i=0; i<nodes.getLength(); i++) {
        node = nodes.item(i);
        NamedNodeMap attrs = node.getAttributes();

        String source = DOMUtil.getAttr(attrs, SOURCE, COPY_FIELD + " definition");
        String dest   = DOMUtil.getAttr(attrs, DESTINATION,  COPY_FIELD + " definition");
        String maxChars = DOMUtil.getAttr(attrs, MAX_CHARS);
        int maxCharsInt = CopyField.UNLIMITED;
        if (maxChars != null) {
          try {
            maxCharsInt = Integer.parseInt(maxChars);
          } catch (NumberFormatException e) {
            log.warn("Couldn't parse " + MAX_CHARS + " attribute for " + COPY_FIELD + " from "
                    + source + " to " + dest + " as integer. The whole field will be copied.");
          }
        }

        if (dest.equals(uniqueKeyFieldName)) {
          String msg = UNIQUE_KEY + " field ("+uniqueKeyFieldName+
            ") can not be the " + DESTINATION + " of a " + COPY_FIELD + "(" + SOURCE + "=" +source+")";
          log.error(msg);
          throw new SolrException(ErrorCode.SERVER_ERROR, msg);
          
        }

        registerCopyField(source, dest, maxCharsInt);
      }
      
      for (Map.Entry<SchemaField, Integer> entry : copyFieldTargetCounts.entrySet()) {
        if (entry.getValue() > 1 && !entry.getKey().multiValued())  {
          log.warn("Field " + entry.getKey().name + " is not multivalued "+
              "and destination for multiple " + COPY_FIELDS + " ("+
              entry.getValue()+")");
        }
      }

      //Run the callbacks on SchemaAware now that everything else is done
      for (SchemaAware aware : schemaAware) {
        aware.inform(this);
      }
    } catch (SolrException e) {
      throw e;
    } catch(Exception e) {
      // unexpected exception...
      throw new SolrException(ErrorCode.SERVER_ERROR, "Schema Parsing Failed: " + e.getMessage(), e);
    }

    // create the field analyzers
    refreshAnalyzers();
  }

  /**
   * Converts a sequence of path steps into a rooted path, by inserting slashes in front of each step.
   * @param steps The steps to join with slashes to form a path
   * @return a rooted path: a leading slash followed by the given steps joined with slashes
   */
  private String stepsToPath(String... steps) {
    StringBuilder builder = new StringBuilder();
    for (String step : steps) { builder.append(SLASH).append(step); }
    return builder.toString();
  }

  /** Returns true if the given name has exactly one asterisk either at the start or end of the name */
  private static boolean isValidFieldGlob(String name) {
    if (name.startsWith("*") || name.endsWith("*")) {
      int count = 0;
      for (int pos = 0 ; pos < name.length() && -1 != (pos = name.indexOf('*', pos)) ; ++pos) ++count;
      if (1 == count) return true;
    }
    return false;
  }
  
  private void addDynamicField(List<DynamicField> dFields, SchemaField f) {
    if (isDuplicateDynField(dFields, f)) {
      String msg = "[schema.xml] Duplicate DynamicField definition for '" + f.getName() + "'";
      throw new SolrException(ErrorCode.SERVER_ERROR, msg);
    } else {
      addDynamicFieldNoDupCheck(dFields, f);
    }
  }

  /**
   * Register one or more new Dynamic Fields with the Schema.
   * @param fields The sequence of {@link org.apache.solr.schema.SchemaField}
   */
  public void registerDynamicFields(SchemaField... fields) {
    List<DynamicField> dynFields = new ArrayList<DynamicField>(Arrays.asList(dynamicFields));
    for (SchemaField field : fields) {
      if (isDuplicateDynField(dynFields, field)) {
        log.debug("dynamic field already exists: dynamic field: [" + field.getName() + "]");
      } else {
        log.debug("dynamic field creation for schema field: " + field.getName());
        addDynamicFieldNoDupCheck(dynFields, field);
      }
    }
    Collections.sort(dynFields);
    dynamicFields = dynFields.toArray(new DynamicField[dynFields.size()]);
  }

  private void addDynamicFieldNoDupCheck(List<DynamicField> dFields, SchemaField f) {
    dFields.add(new DynamicField(f));
    log.debug("dynamic field defined: " + f);
  }

  private boolean isDuplicateDynField(List<DynamicField> dFields, SchemaField f) {
    for (DynamicField df : dFields) {
      if (df.getRegex().equals(f.name)) return true;
    }
    return false;
  }

  public void registerCopyField( String source, String dest ) {
    registerCopyField(source, dest, CopyField.UNLIMITED);
  }

  /**
   * <p>
   * NOTE: this function is not thread safe.  However, it is safe to use within the standard
   * <code>inform( SolrCore core )</code> function for <code>SolrCoreAware</code> classes.
   * Outside <code>inform</code>, this could potentially throw a ConcurrentModificationException
   * </p>
   * 
   * @see SolrCoreAware
   */
  public void registerCopyField(String source, String dest, int maxChars) {
    log.debug(COPY_FIELD + " " + SOURCE + "='" + source + "' " + DESTINATION + "='" + dest
              + "' " + MAX_CHARS + "=" + maxChars);

    DynamicField destDynamicField = null;
    SchemaField destSchemaField = fields.get(dest);
    SchemaField sourceSchemaField = fields.get(source);
    
    DynamicField sourceDynamicBase = null;
    DynamicField destDynamicBase = null;
    
    boolean sourceIsDynamicFieldReference = false;
    boolean sourceIsExplicitFieldGlob = false;
    
    if (null == sourceSchemaField && isValidFieldGlob(source)) {
      Pattern pattern = Pattern.compile(source.replace("*", ".*")); // glob->regex
      for (String field : fields.keySet()) {
        if (pattern.matcher(field).matches()) {
          sourceIsExplicitFieldGlob = true;
          break;
        }
      }
    }
    
    if (null == destSchemaField || (null == sourceSchemaField && ! sourceIsExplicitFieldGlob)) {
      // Go through dynamicFields array only once, collecting info for both source and dest fields, if needed
      for (DynamicField dynamicField : dynamicFields) {
        if (null == sourceSchemaField && ! sourceIsDynamicFieldReference && ! sourceIsExplicitFieldGlob) {
          if (dynamicField.matches(source)) {
            sourceIsDynamicFieldReference = true;
            if ( ! source.equals(dynamicField.getRegex())) {
              sourceDynamicBase = dynamicField;
            }
          }
        }
        if (null == destSchemaField) {
          if (dest.equals(dynamicField.getRegex())) {
            destDynamicField = dynamicField;
            destSchemaField = dynamicField.prototype;
          } else if (dynamicField.matches(dest)) {
            destSchemaField = dynamicField.makeSchemaField(dest);
            destDynamicField = new DynamicField(destSchemaField);
            destDynamicBase = dynamicField;
          }
        }
        if (null != destSchemaField 
            && (null != sourceSchemaField || sourceIsDynamicFieldReference || sourceIsExplicitFieldGlob)) {
          break;
        }
      }
    }
    if (null == sourceSchemaField && ! sourceIsDynamicFieldReference && ! sourceIsExplicitFieldGlob) {
      String msg = "copyField source :'" + source + "' doesn't match any explicit field or dynamicField.";
      throw new SolrException(ErrorCode.SERVER_ERROR, msg);
    }
    if (null == destSchemaField) {
      String msg = "copyField dest :'" + dest + "' is not an explicit field and doesn't match a dynamicField.";
      throw new SolrException(ErrorCode.SERVER_ERROR, msg);
    }
    if (sourceIsDynamicFieldReference || sourceIsExplicitFieldGlob) {
      if (null != destDynamicField) { // source: dynamic field ref or explicit field glob; dest: dynamic field ref
        registerDynamicCopyField(new DynamicCopy(source, destDynamicField, maxChars, sourceDynamicBase, destDynamicBase));
        incrementCopyFieldTargetCount(destSchemaField);
      } else {                        // source: dynamic field reference; dest: explicit field
        destDynamicField = new DynamicField(destSchemaField);
        registerDynamicCopyField(new DynamicCopy(source, destDynamicField, maxChars, sourceDynamicBase, null));
        incrementCopyFieldTargetCount(destSchemaField);
      }
    } else {                          
      if (null != destDynamicField) { // source: explicit field; dest: dynamic field reference
        if (destDynamicField.pattern instanceof DynamicReplacement.DynamicPattern.NameEquals) {
          // Dynamic dest with no asterisk is acceptable
          registerDynamicCopyField(new DynamicCopy(source, destDynamicField, maxChars, sourceDynamicBase, destDynamicBase));
          incrementCopyFieldTargetCount(destSchemaField);
        } else {
          String msg = "copyField only supports a dynamic destination with an asterisk "
                     + "if the source also has an asterisk";
          throw new SolrException(ErrorCode.SERVER_ERROR, msg);
        }
      } else {                        // source & dest: explicit fields 
        List<CopyField> copyFieldList = copyFieldsMap.get(source);
        if (copyFieldList == null) {
          copyFieldList = new ArrayList<CopyField>();
          copyFieldsMap.put(source, copyFieldList);
        }
        copyFieldList.add(new CopyField(sourceSchemaField, destSchemaField, maxChars));
        incrementCopyFieldTargetCount(destSchemaField);
      }
    }
  }
  
  private void incrementCopyFieldTargetCount(SchemaField dest) {
    copyFieldTargetCounts.put(dest, copyFieldTargetCounts.containsKey(dest) ? copyFieldTargetCounts.get(dest) + 1 : 1);
  }
  
  private void registerDynamicCopyField( DynamicCopy dcopy ) {
    if( dynamicCopyFields == null ) {
      dynamicCopyFields = new DynamicCopy[] {dcopy};
    }
    else {
      DynamicCopy[] temp = new DynamicCopy[dynamicCopyFields.length+1];
      System.arraycopy(dynamicCopyFields,0,temp,0,dynamicCopyFields.length);
      temp[temp.length -1] = dcopy;
      dynamicCopyFields = temp;
    }
    log.trace("Dynamic Copy Field:" + dcopy);
  }

  static SimilarityFactory readSimilarity(SolrResourceLoader loader, Node node) {
    if (node==null) {
      return null;
    } else {
      SimilarityFactory similarityFactory;
      final Object obj = loader.newInstance(((Element) node).getAttribute("class"), Object.class, "search.similarities.");
      if (obj instanceof SimilarityFactory) {
        // configure a factory, get a similarity back
        SolrParams params = SolrParams.toSolrParams(DOMUtil.childNodesToNamedList(node));
        similarityFactory = (SimilarityFactory)obj;
        similarityFactory.init(params);
      } else {
        // just like always, assume it's a Similarity and get a ClassCastException - reasonable error handling
        similarityFactory = new SimilarityFactory() {
          @Override
          public Similarity getSimilarity() {
            return (Similarity) obj;
          }
        };
      }
      return similarityFactory;
    }
  }


  public static abstract class DynamicReplacement implements Comparable<DynamicReplacement> {
    abstract protected static class DynamicPattern {
      protected final String regex;
      protected final String fixedStr;

      protected DynamicPattern(String regex, String fixedStr) { this.regex = regex; this.fixedStr = fixedStr; }

      static DynamicPattern createPattern(String regex) {
        if (regex.startsWith("*")) { return new NameEndsWith(regex); }
        else if (regex.endsWith("*")) { return new NameStartsWith(regex); }
        else { return new NameEquals(regex);
        }
      }
      
      /** Returns true if the given name matches this pattern */
      abstract boolean matches(String name);

      /** Returns the remainder of the given name after removing this pattern's fixed string component */
      abstract String remainder(String name);

      /** Returns the result of combining this pattern's fixed string component with the given replacement */
      abstract String subst(String replacement);
      
      /** Returns the length of the original regex, including the asterisk, if any. */
      public int length() { return regex.length(); }

      private static class NameStartsWith extends DynamicPattern {
        NameStartsWith(String regex) { super(regex, regex.substring(0, regex.length() - 1)); }
        boolean matches(String name) { return name.startsWith(fixedStr); }
        String remainder(String name) { return name.substring(fixedStr.length()); }
        String subst(String replacement) { return fixedStr + replacement; }
      }
      private static class NameEndsWith extends DynamicPattern {
        NameEndsWith(String regex) { super(regex, regex.substring(1)); }
        boolean matches(String name) { return name.endsWith(fixedStr); }
        String remainder(String name) { return name.substring(0, name.length() - fixedStr.length()); }
        String subst(String replacement) { return replacement + fixedStr; }
      }
      private static class NameEquals extends DynamicPattern {
        NameEquals(String regex) { super(regex, regex); }
        boolean matches(String name) { return regex.equals(name); }
        String remainder(String name) { return ""; }
        String subst(String replacement) { return fixedStr; }
      }
    }

    protected DynamicPattern pattern;

    public boolean matches(String name) { return pattern.matches(name); }

    protected DynamicReplacement(String regex) {
      pattern = DynamicPattern.createPattern(regex);
    }

    /**
     * Sort order is based on length of regex.  Longest comes first.
     * @param other The object to compare to.
     * @return a negative integer, zero, or a positive integer
     * as this object is less than, equal to, or greater than
     * the specified object.
     */
    @Override
    public int compareTo(DynamicReplacement other) {
      return other.pattern.length() - pattern.length();
    }
    
    /** Returns the regex used to create this instance's pattern */
    public String getRegex() {
      return pattern.regex;
    }
  }


  public final static class DynamicField extends DynamicReplacement {
    private final SchemaField prototype;
    public SchemaField getPrototype() { return prototype; }

    DynamicField(SchemaField prototype) {
      super(prototype.name);
      this.prototype=prototype;
    }

    SchemaField makeSchemaField(String name) {
      // could have a cache instead of returning a new one each time, but it might
      // not be worth it.
      // Actually, a higher level cache could be worth it to avoid too many
      // .startsWith() and .endsWith() comparisons.  it depends on how many
      // dynamic fields there are.
      return new SchemaField(prototype, name);
    }

    @Override
    public String toString() {
      return prototype.toString();
    }
  }

  public static class DynamicCopy extends DynamicReplacement {
    private final DynamicField destination;
    
    private final int maxChars;
    public int getMaxChars() { return maxChars; }

    final DynamicField sourceDynamicBase;
    public DynamicField getSourceDynamicBase() { return sourceDynamicBase; }

    final DynamicField destDynamicBase;
    public DynamicField getDestDynamicBase() { return destDynamicBase; }

    DynamicCopy(String sourceRegex, DynamicField destination, int maxChars, 
                DynamicField sourceDynamicBase, DynamicField destDynamicBase) {
      super(sourceRegex);
      this.destination = destination;
      this.maxChars = maxChars;
      this.sourceDynamicBase = sourceDynamicBase;
      this.destDynamicBase = destDynamicBase;
    }

    public String getDestFieldName() { return destination.getRegex(); }

    /**
     *  Generates a destination field name based on this source pattern,
     *  by substituting the remainder of this source pattern into the
     *  the given destination pattern.
     */
    public SchemaField getTargetField(String sourceField) {
      String remainder = pattern.remainder(sourceField);
      String targetFieldName = destination.pattern.subst(remainder);
      return destination.makeSchemaField(targetFieldName);
    }

    
    @Override
    public String toString() {
      return destination.prototype.toString();
    }
  }

  public SchemaField[] getDynamicFieldPrototypes() {
    SchemaField[] df = new SchemaField[dynamicFields.length];
    for (int i=0;i<dynamicFields.length;i++) {
      df[i] = dynamicFields[i].prototype;
    }
    return df;
  }

  public String getDynamicPattern(String fieldName) {
   for (DynamicField df : dynamicFields) {
     if (df.matches(fieldName)) return df.getRegex();
   }
   return  null; 
  }
  
  /**
   * Does the schema explicitly define the specified field, i.e. not as a result
   * of a copyField declaration?  We consider it explicitly defined if it matches
   * a field name or a dynamicField name.
   * @return true if explicitly declared in the schema.
   */
  public boolean hasExplicitField(String fieldName) {
    if (fields.containsKey(fieldName)) {
      return true;
    }

    for (DynamicField df : dynamicFields) {
      if (fieldName.equals(df.getRegex())) return true;
    }

    return false;
  }

  /**
   * Is the specified field dynamic or not.
   * @return true if the specified field is dynamic
   */
  public boolean isDynamicField(String fieldName) {
    if(fields.containsKey(fieldName)) {
      return false;
    }

    for (DynamicField df : dynamicFields) {
      if (df.matches(fieldName)) return true;
    }

    return false;
  }   

  /**
   * Returns the SchemaField that should be used for the specified field name, or
   * null if none exists.
   *
   * @param fieldName may be an explicitly defined field or a name that
   * matches a dynamic field.
   * @see #getFieldType
   * @see #getField(String)
   * @return The {@link org.apache.solr.schema.SchemaField}
   */
  public SchemaField getFieldOrNull(String fieldName) {
    SchemaField f = fields.get(fieldName);
    if (f != null) return f;

    for (DynamicField df : dynamicFields) {
      if (df.matches(fieldName)) return df.makeSchemaField(fieldName);
    }

    return f;
  }

  /**
   * Returns the SchemaField that should be used for the specified field name
   *
   * @param fieldName may be an explicitly defined field or a name that
   * matches a dynamic field.
   * @throws SolrException if no such field exists
   * @see #getFieldType
   * @see #getFieldOrNull(String)
   * @return The {@link SchemaField}
   */
  public SchemaField getField(String fieldName) {
    SchemaField f = getFieldOrNull(fieldName);
    if (f != null) return f;


    // Hmmm, default field could also be implemented with a dynamic field of "*".
    // It would have to be special-cased and only used if nothing else matched.
    /***  REMOVED -YCS
    if (defaultFieldType != null) return new SchemaField(fieldName,defaultFieldType);
    ***/
    throw new SolrException(ErrorCode.BAD_REQUEST,"undefined field: \""+fieldName+"\"");
  }

  /**
   * Returns the FieldType for the specified field name.
   *
   * <p>
   * This method exists because it can be more efficient then
   * {@link #getField} for dynamic fields if a full SchemaField isn't needed.
   * </p>
   *
   * @param fieldName may be an explicitly created field, or a name that
   *  excercises a dynamic field.
   * @throws SolrException if no such field exists
   * @see #getField(String)
   * @see #getFieldTypeNoEx
   */
  public FieldType getFieldType(String fieldName) {
    SchemaField f = fields.get(fieldName);
    if (f != null) return f.getType();

    return getDynamicFieldType(fieldName);
  }

  /**
   * Given the name of a {@link org.apache.solr.schema.FieldType} (not to be confused with {@link #getFieldType(String)} which
   * takes in the name of a field), return the {@link org.apache.solr.schema.FieldType}.
   * @param fieldTypeName The name of the {@link org.apache.solr.schema.FieldType}
   * @return The {@link org.apache.solr.schema.FieldType} or null.
   */
  public FieldType getFieldTypeByName(String fieldTypeName){
    return fieldTypes.get(fieldTypeName);
  }

  /**
   * Returns the FieldType for the specified field name.
   *
   * <p>
   * This method exists because it can be more efficient then
   * {@link #getField} for dynamic fields if a full SchemaField isn't needed.
   * </p>
   *
   * @param fieldName may be an explicitly created field, or a name that
   * exercises a dynamic field.
   * @return null if field is not defined.
   * @see #getField(String)
   * @see #getFieldTypeNoEx
   */
  public FieldType getFieldTypeNoEx(String fieldName) {
    SchemaField f = fields.get(fieldName);
    if (f != null) return f.getType();
    return dynFieldType(fieldName);
  }


  /**
   * Returns the FieldType of the best matching dynamic field for
   * the specified field name
   *
   * @param fieldName may be an explicitly created field, or a name that
   * exercises a dynamic field.
   * @throws SolrException if no such field exists
   * @see #getField(String)
   * @see #getFieldTypeNoEx
   */
  public FieldType getDynamicFieldType(String fieldName) {
     for (DynamicField df : dynamicFields) {
      if (df.matches(fieldName)) return df.prototype.getType();
    }
    throw new SolrException(ErrorCode.BAD_REQUEST,"undefined field "+fieldName);
  }

  private FieldType dynFieldType(String fieldName) {
     for (DynamicField df : dynamicFields) {
      if (df.matches(fieldName)) return df.prototype.getType();
    }
    return null;
  };


  /**
   * Get all copy fields, both the static and the dynamic ones.
   * @return Array of fields copied into this field
   */

  public SchemaField[] getCopySources(String destField) {
    SchemaField f = getField(destField);
    if (!isCopyFieldTarget(f)) {
      return new SchemaField[0];
    }
    List<SchemaField> sf = new ArrayList<SchemaField>();
    for (Map.Entry<String, List<CopyField>> cfs : copyFieldsMap.entrySet()) {
      for (CopyField copyField : cfs.getValue()) {
        if (copyField.getDestination().getName().equals(destField)) {
          sf.add(copyField.getSource());
        }
      }
    }
    for (DynamicCopy dynamicCopy : dynamicCopyFields) {
      if (dynamicCopy.getDestFieldName().equals(destField)) {
        sf.add(getField(dynamicCopy.getRegex()));
      }
    }
    return sf.toArray(new SchemaField[sf.size()]);
  }

  /**
   * Get all copy fields for a specified source field, both static
   * and dynamic ones.
   * @return List of CopyFields to copy to.
   * @since solr 1.4
   */
  // This is useful when we need the maxSize param of each CopyField
  public List<CopyField> getCopyFieldsList(final String sourceField){
    final List<CopyField> result = new ArrayList<CopyField>();
    for (DynamicCopy dynamicCopy : dynamicCopyFields) {
      if (dynamicCopy.matches(sourceField)) {
        result.add(new CopyField(getField(sourceField), dynamicCopy.getTargetField(sourceField), dynamicCopy.maxChars));
      }
    }
    List<CopyField> fixedCopyFields = copyFieldsMap.get(sourceField);
    if (null != fixedCopyFields) {
      result.addAll(fixedCopyFields);
    }

    return result;
  }
  
  /**
   * Check if a field is used as the destination of a copyField operation 
   * 
   * @since solr 1.3
   */
  public boolean isCopyFieldTarget( SchemaField f ) {
    return copyFieldTargetCounts.containsKey( f );
  }

  /**
   * Get a map of property name -> value for this field.
   */
  public SimpleOrderedMap<Object> getNamedPropertyValues() {
    SimpleOrderedMap<Object> topLevel = new SimpleOrderedMap<Object>();
    topLevel.add(NAME, getSchemaName());
    topLevel.add(VERSION, getVersion());
    if (null != uniqueKeyFieldName) {
      topLevel.add(UNIQUE_KEY, uniqueKeyFieldName);
    }
    if (null != defaultSearchFieldName) {
      topLevel.add(DEFAULT_SEARCH_FIELD, defaultSearchFieldName);
    }
    if (isExplicitQueryParserDefaultOperator) {
      SimpleOrderedMap<Object> solrQueryParserProperties = new SimpleOrderedMap<Object>();
      solrQueryParserProperties.add(DEFAULT_OPERATOR, queryParserDefaultOperator);
      topLevel.add(SOLR_QUERY_PARSER, solrQueryParserProperties);
    }
    if (isExplicitSimilarity) {
      topLevel.add(SIMILARITY, similarityFactory.getNamedPropertyValues());
    }
    List<SimpleOrderedMap<Object>> fieldTypeProperties = new ArrayList<SimpleOrderedMap<Object>>();
    SortedMap<String,FieldType> sortedFieldTypes = new TreeMap<String,FieldType>(fieldTypes);
    for (FieldType fieldType : sortedFieldTypes.values()) {
      fieldTypeProperties.add(fieldType.getNamedPropertyValues(false));
    }
    topLevel.add(FIELD_TYPES, fieldTypeProperties);  
    List<SimpleOrderedMap<Object>> fieldProperties = new ArrayList<SimpleOrderedMap<Object>>();
    SortedSet<String> fieldNames = new TreeSet<String>(fields.keySet());
    for (String fieldName : fieldNames) {
      fieldProperties.add(fields.get(fieldName).getNamedPropertyValues(false));
    }
    topLevel.add(FIELDS, fieldProperties);
    List<SimpleOrderedMap<Object>> dynamicFieldProperties = new ArrayList<SimpleOrderedMap<Object>>();
    for (IndexSchema.DynamicField dynamicField : dynamicFields) {
      if ( ! dynamicField.getRegex().startsWith(INTERNAL_POLY_FIELD_PREFIX)) { // omit internal polyfields
        dynamicFieldProperties.add(dynamicField.getPrototype().getNamedPropertyValues(false));
      }
    }
    topLevel.add(DYNAMIC_FIELDS, dynamicFieldProperties);
    topLevel.add(COPY_FIELDS, getCopyFieldProperties(false, null, null));
    return topLevel;
  }

  /**
   * Returns a list of copyField directives, with optional details and optionally restricting to those
   * directives that contain the requested source and/or destination field names.
   * 
   * @param showDetails If true, source and destination dynamic bases, and explicit fields matched by source globs,
   *                    will be added to dynamic copyField directives where appropriate
   * @param requestedSourceFields If not null, output is restricted to those copyField directives
   *                              with the requested source field names 
   * @param requestedDestinationFields If not null, output is restricted to those copyField directives
   *                                   with the requested destination field names 
   * @return a list of copyField directives 
   */
  public List<SimpleOrderedMap<Object>> getCopyFieldProperties
      (boolean showDetails, Set<String> requestedSourceFields, Set<String> requestedDestinationFields) {
    List<SimpleOrderedMap<Object>> copyFieldProperties = new ArrayList<SimpleOrderedMap<Object>>();
    SortedMap<String,List<CopyField>> sortedCopyFields = new TreeMap<String,List<CopyField>>(copyFieldsMap);
    for (List<CopyField> copyFields : sortedCopyFields.values()) {
      Collections.sort(copyFields, new Comparator<CopyField>() {
        @Override
        public int compare(CopyField cf1, CopyField cf2) {
          // sources are all be the same, just sorting by destination here
          return cf1.getDestination().getName().compareTo(cf2.getDestination().getName());
        }
      });
      for (CopyField copyField : copyFields) {
        final String source = copyField.getSource().getName();
        final String destination = copyField.getDestination().getName();
        if (   (null == requestedSourceFields      || requestedSourceFields.contains(source))
            && (null == requestedDestinationFields || requestedDestinationFields.contains(destination))) {
          SimpleOrderedMap<Object> props = new SimpleOrderedMap<Object>();
          props.add(SOURCE, source);
          props.add(DESTINATION, destination);
            if (0 != copyField.getMaxChars()) {
              props.add(MAX_CHARS, copyField.getMaxChars());
            }
          copyFieldProperties.add(props);
        }
      }
    }
    for (IndexSchema.DynamicCopy dynamicCopy : dynamicCopyFields) {
      final String source = dynamicCopy.getRegex();
      final String destination = dynamicCopy.getDestFieldName();
      if (   (null == requestedSourceFields      || requestedSourceFields.contains(source))
          && (null == requestedDestinationFields || requestedDestinationFields.contains(destination))) {
        SimpleOrderedMap<Object> dynamicCopyProps = new SimpleOrderedMap<Object>();

        dynamicCopyProps.add(SOURCE, dynamicCopy.getRegex());
        if (showDetails) {
          IndexSchema.DynamicField sourceDynamicBase = dynamicCopy.getSourceDynamicBase();
          if (null != sourceDynamicBase) {
            dynamicCopyProps.add(SOURCE_DYNAMIC_BASE, sourceDynamicBase.getRegex());
          } else if (source.contains("*")) {
            List<String> sourceExplicitFields = new ArrayList<String>();
            Pattern pattern = Pattern.compile(source.replace("*", ".*"));   // glob->regex
            for (String field : fields.keySet()) {
              if (pattern.matcher(field).matches()) {
                sourceExplicitFields.add(field);
              }
            }
            if (sourceExplicitFields.size() > 0) {
              Collections.sort(sourceExplicitFields);
              dynamicCopyProps.add(SOURCE_EXPLICIT_FIELDS, sourceExplicitFields);
            }
          }
        }
        
        dynamicCopyProps.add(DESTINATION, dynamicCopy.getDestFieldName());
        if (showDetails) {
          IndexSchema.DynamicField destDynamicBase = dynamicCopy.getDestDynamicBase();
          if (null != destDynamicBase) {
            dynamicCopyProps.add(DESTINATION_DYNAMIC_BASE, destDynamicBase.getRegex());
          }
        }

        if (0 != dynamicCopy.getMaxChars()) {
          dynamicCopyProps.add(MAX_CHARS, dynamicCopy.getMaxChars());
        }

        copyFieldProperties.add(dynamicCopyProps);
      }
    }
    return copyFieldProperties;
  }
}
