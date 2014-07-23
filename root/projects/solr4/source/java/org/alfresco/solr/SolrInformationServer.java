/*
 * Copyright (C) 2014 Alfresco Software Limited.
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */

package org.alfresco.solr;

import static org.alfresco.repo.search.adaptor.lucene.QueryConstants.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.alfresco.httpclient.AuthenticationException;
import org.alfresco.model.ContentModel;
import org.alfresco.opencmis.dictionary.CMISStrictDictionaryService;
import org.alfresco.repo.content.ContentContext;
import org.alfresco.repo.dictionary.DictionaryComponent;
import org.alfresco.repo.dictionary.M2Model;
import org.alfresco.repo.dictionary.NamespaceDAO;
import org.alfresco.service.cmr.dictionary.AspectDefinition;
import org.alfresco.service.cmr.dictionary.TypeDefinition;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.datatype.DefaultTypeConverter;
import org.alfresco.service.cmr.security.AuthorityType;
import org.alfresco.service.namespace.QName;
import org.alfresco.solr.AlfrescoSolrDataModel.FieldInstance;
import org.alfresco.solr.AlfrescoSolrDataModel.IndexedField;
import org.alfresco.solr.adapters.IOpenBitSet;
import org.alfresco.solr.adapters.ISimpleOrderedMap;
import org.alfresco.solr.adapters.SolrOpenBitSetAdapter;
import org.alfresco.solr.adapters.SolrSimpleOrderedMap;
import org.alfresco.solr.client.AclChangeSet;
import org.alfresco.solr.client.AclReaders;
import org.alfresco.solr.client.AlfrescoModel;
import org.alfresco.solr.client.ContentPropertyValue;
import org.alfresco.solr.client.MLTextPropertyValue;
import org.alfresco.solr.client.MultiPropertyValue;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.client.Node.SolrApiNodeStatus;
import org.alfresco.solr.client.NodeMetaData;
import org.alfresco.solr.client.NodeMetaDataParameters;
import org.alfresco.solr.client.PropertyValue;
import org.alfresco.solr.client.SOLRAPIClient;
import org.alfresco.solr.client.SOLRAPIClient.GetTextContentResponse;
import org.alfresco.solr.client.StringPropertyValue;
import org.alfresco.solr.client.Transaction;
import org.alfresco.solr.content.SolrContentStore;
import org.alfresco.solr.content.SolrContentUrlBuilder;
import org.alfresco.solr.tracker.IndexHealthReport;
import org.alfresco.solr.tracker.TrackerStats;
import org.alfresco.util.ISO9075;
import org.alfresco.util.NumericEncoder;
import org.alfresco.util.Pair;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.NumericUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.FastInputStream;
import org.apache.solr.common.util.FastOutputStream;
import org.apache.solr.common.util.JavaBinCodec;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrInfoMBean;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.response.ResultContext;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.BitDocSet;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.CommitUpdateCommand;
import org.apache.solr.update.DeleteUpdateCommand;
import org.apache.solr.update.RollbackUpdateCommand;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.apache.solr.util.RefCounted;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.extensions.surf.util.I18NUtil;
import org.springframework.util.FileCopyUtils;

/**
 * This is the Solr4 implementation of the information server (index).
 * @author Ahmed Owian
 */
public class SolrInformationServer implements InformationServer
{
    private static final String OR = " OR ";
    private AlfrescoCoreAdminHandler adminHandler;
    private SolrCore core;
    private TrackerStats trackerStats = new TrackerStats(this);
    private AlfrescoSolrDataModel dataModel;
    private SolrContentStore solrContentStore;
    private String alfrescoVersion;
    private int authorityCacheSize;
    private int filterCacheSize;
    private int pathCacheSize;
    private boolean transformContent = true;
    private long lag;
    private long holeRetention;
    
    // Metadata pulling control
    private boolean skipDescendantAuxDocsForSpecificTypes;
    private Set<QName> typesForSkippingDescendantAuxDocs = new HashSet<QName>();
    private BooleanQuery skippingDocsQuery;
    private SOLRAPIClient repositoryClient;
    
    protected final static Logger log = LoggerFactory.getLogger(SolrInformationServer.class);
    protected enum FTSStatus {New, Dirty, Clean}; //TODO store this somewhere common
    
    
    // write a BytesRef as a byte array
    JavaBinCodec.ObjectResolver resolver = new JavaBinCodec.ObjectResolver() {
      @Override
      public Object resolve(Object o, JavaBinCodec codec) throws IOException {
        if (o instanceof BytesRef) {
          BytesRef br = (BytesRef)o;
          codec.writeByteArray(br.bytes, br.offset, br.length);
          return null;
        }
        return o;
      }
    };
    

    public SolrInformationServer(AlfrescoCoreAdminHandler adminHandler, SolrCore core, SOLRAPIClient repositoryClient,
                SolrContentStore solrContentStore)
    {
        this.adminHandler = adminHandler;
        this.core = core;
        this.repositoryClient = repositoryClient;
        this.solrContentStore = solrContentStore;

        Properties p = core.getResourceLoader().getCoreProperties();
        alfrescoVersion = p.getProperty("alfresco.version", "4.2.2");
        authorityCacheSize = Integer.parseInt(p.getProperty("solr.authorityCache.size", "64"));
        filterCacheSize = Integer.parseInt(p.getProperty("solr.filterCache.size", "64"));
        pathCacheSize = Integer.parseInt(p.getProperty("solr.pathCache.size", "64"));
        transformContent = Boolean.parseBoolean(p.getProperty("alfresco.index.transformContent", "true"));
        lag = Integer.parseInt(p.getProperty("alfresco.lag", "1000"));
        holeRetention = Integer.parseInt(p.getProperty("alfresco.hole.retention", "3600000"));
        
        dataModel = AlfrescoSolrDataModel.getInstance();
        

        skipDescendantAuxDocsForSpecificTypes = Boolean.parseBoolean(p.getProperty("alfresco.metadata.skipDescendantAuxDocsForSpecificTypes", "false"));

        if (skipDescendantAuxDocsForSpecificTypes)
        {
            int i = 0;
            skippingDocsQuery = new BooleanQuery();
            for (String key = new StringBuilder(PROP_PREFIX_PARENT_TYPE).append(i).toString(); p.containsKey(key); key = new StringBuilder(PROP_PREFIX_PARENT_TYPE).append(++i)
                    .toString())
            {
                String qName = p.getProperty(key);
                if ((null != qName) && !qName.isEmpty())
                {
                    QName typeQName = QName.resolveToQName(dataModel.getNamespaceDAO(), qName);
                    TypeDefinition type = dataModel.getDictionaryService(CMISStrictDictionaryService.DEFAULT).getType(typeQName);
                    if (null != type)
                    {
                        typesForSkippingDescendantAuxDocs.add(typeQName);
                        skippingDocsQuery.add(new TermQuery(new Term(FIELD_TYPE, typeQName.toString())), Occur.SHOULD);
                    }
                }
            }
        }
    }

    public String getAlfrescoVersion()
    {
        return this.alfrescoVersion;
    }
    
    @Override
    public void afterInitModels()
    {
        this.dataModel.afterInitModels();
    }

    @Override
    public AclReport checkAclInIndex(Long aclid, AclReport aclReport)
    {
        try
        {
            RefCounted<SolrIndexSearcher> refCounted = core.getSearcher(false, true, null);

            refCounted = core.getSearcher(false, true, null);
            if (refCounted == null)
            {
                return aclReport;
            }

            try
            {
                SolrIndexSearcher solrIndexSearcher = refCounted.get();

                String aclIdString = NumericEncoder.encode(aclid);
                DocSet docSet = solrIndexSearcher.getDocSet(new TermQuery(new Term("ACLID", aclIdString)));
                // should find leaf and aux
                for (DocIterator it = docSet.iterator(); it.hasNext(); /* */)
                {
                    int doc = it.nextDoc();

                    Document document = solrIndexSearcher.doc(doc);
                    IndexableField fieldable = document.getField("ID");
                    if (fieldable != null)
                    {
                        String value = fieldable.stringValue();
                        if (value != null)
                        {
                            if (value.startsWith("ACL-"))
                            {
                                aclReport.setIndexAclDoc(Long.valueOf(doc));
                            }
                        }
                    }

                }
                DocSet txDocSet = solrIndexSearcher.getDocSet(new WildcardQuery(new Term("ACLTXID", "*")));
                for (DocIterator it = txDocSet.iterator(); it.hasNext(); /* */)
                {
                    int doc = it.nextDoc();
                    Document document = solrIndexSearcher.doc(doc);
                    IndexableField fieldable = document.getField("ACLTXID");
                    if (fieldable != null)
                    {

                        if ((aclReport.getIndexAclDoc() == null) || (doc < aclReport.getIndexAclDoc().longValue()))
                        {
                            String value = fieldable.stringValue();
                            long acltxid = Long.parseLong(value);
                            aclReport.setIndexAclTx(acltxid);
                        }

                    }
                }

            }
            finally
            {
                refCounted.decref();
            }

        }
        catch (IOException e)
        {

        }

        return aclReport;
    }

    @Override
    public void checkCache() throws IOException
    {
       // There is no cache to check for SOLR 4
    }

    @Override
    public IndexHealthReport checkIndexTransactions(IndexHealthReport indexHealthReport, Long minTxId, Long minAclTxId,
                IOpenBitSet txIdsInDb, long maxTxId, IOpenBitSet aclTxIdsInDb, long maxAclTxId) throws IOException
    {
        // TODO Auto-generated method stub
        return null;
    }
    
    public class TenantAndDbId
    {
        public Long dbId;
        public String tenant;
    }
    
    @Override
    public List<TenantAndDbId> getDocsWithUncleanContent() throws IOException
    {
        LocalSolrQueryRequest solrReq = new LocalSolrQueryRequest(core, new NamedList<>());
        ModifiableSolrParams params = new ModifiableSolrParams(solrReq.getParams());
        String query = FIELD_FTSSTATUS + ":" + FTSStatus.Dirty + " OR " + FIELD_FTSSTATUS + ":" + FTSStatus.New;
// TODO batch this query instead of getting all rows at once
        params.set("q", query).set("fl", "id").set("rows", "" + Integer.MAX_VALUE);
        ResultContext rc = cloudSelect(solrReq, params);
        
        List<TenantAndDbId> docs = new ArrayList<>();
        if (rc != null)
        {
            for(DocIterator it = rc.docs.iterator(); it.hasNext(); /**/)
            {
                int docID = it.nextDoc();
                BytesRef tenant = new BytesRef();
                solrReq.getSearcher().getAtomicReader().getSortedDocValues(FIELD_TENANT).get(docID, tenant);
                
                BytesRef dbid = new BytesRef();
                SortedSetDocValues ssdv = solrReq.getSearcher().getAtomicReader().getSortedSetDocValues(FIELD_DBID);
                ssdv.setDocument(docID);
                long ordinal = ssdv.nextOrd();
                if(ordinal == SortedSetDocValues.NO_MORE_ORDS)
                {
                    continue;
                }
                ssdv.lookupOrd(ordinal, dbid);
             
                TenantAndDbId tenantAndDbId = new TenantAndDbId();
                tenantAndDbId.tenant = tenant.utf8ToString();
                tenantAndDbId.dbId = NumericUtils.prefixCodedToLong(dbid);
                
                docs.add(tenantAndDbId);
            }
        }
        return docs;
    }
    
    private ResultContext cloudSelect(LocalSolrQueryRequest solrReq, SolrParams params)
    {
        SolrRequestHandler requestHandler = core.getRequestHandler("/select");
        solrReq.setParams(params);
        log.info("Running query " + params.get("q"));
        SolrQueryResponse solrRsp = new SolrQueryResponse();
        requestHandler.handleRequest(solrReq, solrRsp);
        ResultContext rc = (ResultContext) solrRsp.getValues().get("response");
        
        return rc;
    }
        
    private boolean cloudSelectReturnsDoc(String query)
    {
        LocalSolrQueryRequest solrReq = new LocalSolrQueryRequest(core, new NamedList<>());
        ModifiableSolrParams params = new ModifiableSolrParams(solrReq.getParams());
        params.set("q", query).set("fl", "id").set("rows", "1");
        ResultContext rc = this.cloudSelect(solrReq, params);

        if (rc != null)
        {
            if (rc.docs != null)
            {
                return rc.docs.iterator().hasNext();
            }
        }
            
        return false;
    }
    
    @Override
    public NodeReport checkNodeCommon(NodeReport nodeReport)
    {
        long dbid = nodeReport.getDbid();
        RefCounted<SolrIndexSearcher> refCounted = null;

        try
        {
            refCounted = core.getSearcher(false, true, null);
            if (refCounted == null) { return nodeReport; }

            try
            {
                SolrIndexSearcher solrIndexSearcher = refCounted.get();

                String dbidString = NumericEncoder.encode(dbid);
                DocSet docSet = solrIndexSearcher.getDocSet(new TermQuery(new Term(FIELD_DBID, dbidString)));
                // should find leaf and aux
                for (DocIterator it = docSet.iterator(); it.hasNext(); /* */)
                {
                    int doc = it.nextDoc();
                    Document document = solrIndexSearcher.doc(doc);
                    IndexableField fieldable = document.getField("ID");
                    if (fieldable != null)
                    {
                        String value = fieldable.stringValue();
                        if (value != null)
                        {
                            if (value.startsWith("LEAF-"))
                            {
                                nodeReport.setIndexLeafDoc(Long.valueOf(doc));
                            }
                            else if (value.startsWith("AUX-"))
                            {
                                nodeReport.setIndexAuxDoc(Long.valueOf(doc));
                            }
                        }
                    }
                }

                DocSet txDocSet = solrIndexSearcher.getDocSet(new WildcardQuery(new Term("TXID", "*")));
                for (DocIterator it = txDocSet.iterator(); it.hasNext(); /* */)
                {
                    int doc = it.nextDoc();
                    Document document = solrIndexSearcher.doc(doc);
                    IndexableField fieldable = document.getField("TXID");
                    if (fieldable != null)
                    {

                        if ((nodeReport.getIndexLeafDoc() == null) || (doc < nodeReport.getIndexLeafDoc().longValue()))
                        {
                            String value = fieldable.stringValue();
                            long txid = Long.parseLong(value);
                            nodeReport.setIndexLeafTx(txid);
                        }
                        if ((nodeReport.getIndexAuxDoc() == null) || (doc < nodeReport.getIndexAuxDoc().longValue()))
                        {
                            String value = fieldable.stringValue();
                            long txid = Long.parseLong(value);
                            nodeReport.setIndexAuxTx(txid);
                        }
                    }
                }
            }
            finally
            {
                refCounted.decref();
            }
        }
        catch (IOException e)
        {
            // TODO: do what here?
        }

        return nodeReport;
    }

    @Override
    public void commit() throws IOException
    {
        UpdateRequestProcessor processor = this.core.getUpdateProcessingChain(null).createProcessor(getLocalSolrQueryRequest(), new SolrQueryResponse());
        try
        {
            processor.processCommit(new CommitUpdateCommand(getLocalSolrQueryRequest(), false));
        }
        finally
        {
            processor.finish();
        }
        //this.core.getUpdateHandler().commit(new CommitUpdateCommand(getLocalSolrQueryRequest(), false));
    }

    @Override
    public void deleteByAclChangeSetId(Long aclChangeSetId) throws IOException
    {
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();

            Query query = new TermQuery(new Term(FIELD_INACLTXID, NumericEncoder.encode(aclChangeSetId)));
            deleteByQuery(solrIndexSearcher, query);
        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }
    

    private void deleteByQuery(SolrIndexSearcher solrIndexSearcher, Query query) throws IOException
    {
        HashSet<String> idsToDelete = new HashSet<String>();

        DocSet docSet = solrIndexSearcher.getDocSet(query);
        if (docSet instanceof BitDocSet)
        {
            BitDocSet source = (BitDocSet) docSet;
            FixedBitSet openBitSet = source.getBits();
            int current = -1;
            while ((current = openBitSet.nextSetBit(current + 1)) != -1)
            {
                Document doc = solrIndexSearcher.doc(current, Collections.singleton(FIELD_ID));
                IndexableField fieldable = doc.getField(FIELD_ID);
                if (fieldable != null)
                {
                    idsToDelete.add(fieldable.stringValue());
                }

            }
        }
        else
        {
            for (DocIterator it = docSet.iterator(); it.hasNext(); /* */)
            {
                Document doc = solrIndexSearcher.doc(it.nextDoc(), Collections.singleton(FIELD_ID));
                IndexableField fieldable = doc.getField(FIELD_ID);
                if (fieldable != null)
                {
                    idsToDelete.add(fieldable.stringValue());
                }
            }
        }

        for (String idToDelete : idsToDelete)
        {
            DeleteUpdateCommand docCmd = new DeleteUpdateCommand(getLocalSolrQueryRequest());
            docCmd.setId(idToDelete);
            core.getUpdateHandler().delete(docCmd);
        }

    }

    @Override
    public void deleteByAclId(Long aclId) throws IOException
    {
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();

            Query query = new TermQuery(new Term(FIELD_ACLID, NumericEncoder.encode(aclId)));
            deleteByQuery(solrIndexSearcher, query);
        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }

    @Override
    public void deleteByNodeId(Long nodeId) throws IOException
    {
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();

            Query query = new TermQuery(new Term(FIELD_DBID, NumericEncoder.encode(nodeId)));
            deleteByQuery(solrIndexSearcher, query);
        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }

    @Override
    public void deleteByTransactionId(Long transactionId) throws IOException
    {
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();
            Query query = new TermQuery(new Term(FIELD_INTXID, NumericEncoder.encode(transactionId)));
            deleteByQuery(solrIndexSearcher, query);
        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }

    @Override
    public List<AlfrescoModel> getAlfrescoModels()
    {
        return this.dataModel.getAlfrescoModels();
    }

    @Override
    public Iterable<Entry<String, Object>> getCoreStats() throws IOException
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public DictionaryComponent getDictionaryService(String alternativeDictionary)
    {
        return this.dataModel.getDictionaryService(alternativeDictionary);
    }

    @Override
    public int getDocSetSize(String targetTxId, String targetTxCommitTime) throws IOException
    {
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();
            BooleanQuery query = new BooleanQuery();
            query.add(new TermQuery(AlfrescoSolrDataModel.getLongTerm(FIELD_TXID, targetTxId)), Occur.MUST);
            query.add(new TermQuery(AlfrescoSolrDataModel.getLongTerm(FIELD_TXCOMMITTIME, targetTxCommitTime)), Occur.MUST);
            DocSet set = solrIndexSearcher.getDocSet(query);

            return set.size();
        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }

    @Override
    public Set<Long> getErrorDocIds() throws IOException
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public long getHoleRetention()
    {
        return this.holeRetention;
    }

    @Override
    public M2Model getM2Model(QName modelQName)
    {
        return this.dataModel.getM2Model(modelQName);
    }

    @Override
    public Map<String, Set<String>> getModelErrors()
    {
        return AlfrescoSolrDataModel.getInstance().getModelErrors();
    }

    @Override
    public NamespaceDAO getNamespaceDAO()
    {
        return this.dataModel.getNamespaceDAO();
    }

    @Override
    public IOpenBitSet getOpenBitSetInstance()
    {
        return new SolrOpenBitSetAdapter();
    }

    @Override
    public int getRegisteredSearcherCount()
    {
        HashSet<String> keys = new HashSet<String>();

        for (String key : core.getInfoRegistry().keySet())
        {
            SolrInfoMBean mBean = core.getInfoRegistry().get(key);
            if (mBean != null)
            {
                if (mBean.getName().equals(SolrIndexSearcher.class.getName()))
                {
                    if (!key.equals("searcher"))
                    {
                        keys.add(key);
                    }
                }
            }
        }

        log.info(".... registered Searchers for " + core.getName() + " = " + keys.size());
        return keys.size();

    }

    @Override
    public <T> ISimpleOrderedMap<T> getSimpleOrderedMapInstance()
    {
        return new SolrSimpleOrderedMap<T>();
    }

    @Override
    public TrackerStats getTrackerStats()
    {
        return this.trackerStats;
    }

    @Override
    public TrackerState getTrackerInitialState()
    {
        TrackerState state = new TrackerState();
        SolrRequestHandler handler = core.getRequestHandler("/get");
        LocalSolrQueryRequest req = getLocalSolrQueryRequest();
        SolrQueryResponse rsp = new SolrQueryResponse();
        
        ModifiableSolrParams newParams = new ModifiableSolrParams(req.getParams());
        newParams.set("ids", "TRACKER!STATE!ACLTX,TRACKER!STATE!TX");
        req.setParams(newParams);
        
        handler.handleRequest(req, rsp);
        
        NamedList values = rsp.getValues();
        SolrDocumentList response = (SolrDocumentList)values.get("response");
        SolrDocument acl = null;
        SolrDocument tx = null;
        if(response.getNumFound() > 0)
        {
            acl = response.get(0);
            if (state.getLastIndexedChangeSetCommitTime() == 0)
            {
                state.setLastIndexedChangeSetCommitTime(Long.parseLong( ((IndexableField)acl.getFieldValue("S_ACLTXCOMMITTIME")).stringValue()));
            }

            if (state.getLastIndexedChangeSetId() == 0)
            {
                state.setLastIndexedChangeSetId(Long.parseLong( ((IndexableField)acl.getFieldValue("S_ACLTXID")).stringValue()));
            }
        }
        if(response.getNumFound() > 1)
        {
            tx = response.get(1);
            if (state.getLastIndexedTxCommitTime() == 0)
            {
                state.setLastIndexedTxCommitTime(Long.parseLong( ((IndexableField)tx.getFieldValue("S_TXCOMMITTIME")).stringValue()));
            }
            if (state.getLastIndexedTxId() == 0)
            {
                state.setLastIndexedTxId(Long.parseLong( ((IndexableField)tx.getFieldValue("S_TXID")).stringValue()));
            }
        }
        
        long startTime = System.currentTimeMillis();
        state.setTimeToStopIndexing(startTime - lag);
        state.setTimeBeforeWhichThereCanBeNoHoles(startTime - holeRetention);

        long timeBeforeWhichThereCanBeNoTxHolesInIndex = state.getLastIndexedTxCommitTime() - holeRetention;
        state.setLastGoodTxCommitTimeInIndex(timeBeforeWhichThereCanBeNoTxHolesInIndex > 0 ? timeBeforeWhichThereCanBeNoTxHolesInIndex : 0);

        long timeBeforeWhichThereCanBeNoChangeSetHolesInIndex = state.getLastIndexedChangeSetCommitTime()
                    - holeRetention;
        state.setLastGoodChangeSetCommitTimeInIndex(timeBeforeWhichThereCanBeNoChangeSetHolesInIndex > 0 ? timeBeforeWhichThereCanBeNoChangeSetHolesInIndex : 0);
        
        return state;
        //return getTrackerInitialStateOld();
    }
    

//    //@Override
//    public TrackerState getTrackerInitialStateOld() throws IOException
//    {
//        TrackerState state = new TrackerState();
//
//        RefCounted<SolrIndexSearcher> refCounted = null;
//        try
//        {
//            refCounted = core.getSearcher(false, true, null);
//            SolrIndexSearcher solrIndexSearcher = refCounted.get();
//            IndexReader reader = solrIndexSearcher.getIndexReader();
//
//            if (state.getLastIndexedTxCommitTime() == 0)
//            {
//                state.setLastIndexedTxCommitTime(getLargestTermValue(reader, FIELD_TXCOMMITTIME, -1L));
//            }
//
//            if (state.getLastIndexedTxId() == 0)
//            {
//                state.setLastIndexedTxId(getLargestTermValue(reader, FIELD_TXID, -1L));
//            }
//
//            if (state.getLastIndexedChangeSetCommitTime() == 0)
//            {
//                state.setLastIndexedChangeSetCommitTime(getLargestTermValue(reader, FIELD_ACLTXCOMMITTIME, -1L));
//            }
//
//            if (state.getLastIndexedChangeSetId() == 0)
//            {
//                state.setLastIndexedChangeSetId(getLargestTermValue(reader, FIELD_ACLTXID, -1L));
//            }
//
//            long startTime = System.currentTimeMillis();
//            state.setTimeToStopIndexing(startTime - lag);
//            state.setTimeBeforeWhichThereCanBeNoHoles(startTime - holeRetention);
//
//            long timeBeforeWhichThereCanBeNoTxHolesInIndex = state.getLastIndexedTxCommitTime() - holeRetention;
//            state.setLastGoodTxCommitTimeInIndex(getLargestTermValue(reader, FIELD_TXCOMMITTIME, timeBeforeWhichThereCanBeNoTxHolesInIndex));
//
//            long timeBeforeWhichThereCanBeNoChangeSetHolesInIndex = state.getLastIndexedChangeSetCommitTime()
//                        - holeRetention;
//            state.setLastGoodChangeSetCommitTimeInIndex(getLargestTermValue(reader, FIELD_ACLTXCOMMITTIME, timeBeforeWhichThereCanBeNoChangeSetHolesInIndex));
//
//            if (state.getLastGoodTxCommitTimeInIndex() > 0)
//            {
//                if (state.getLastIndexedTxIdBeforeHoles() == -1)
//                {
//                    state.setLastIndexedTxIdBeforeHoles(getStoredLongByLongTerm(reader, FIELD_TXCOMMITTIME, FIELD_TXID, state.getLastGoodTxCommitTimeInIndex()));
//                            
//                }
//                else
//                {
//                    long currentBestFromIndex = getStoredLongByLongTerm(reader, FIELD_TXCOMMITTIME, FIELD_TXID, state.getLastGoodTxCommitTimeInIndex());
//                    if (currentBestFromIndex > state.getLastIndexedTxIdBeforeHoles())
//                    {
//                        state.setLastIndexedTxIdBeforeHoles(currentBestFromIndex);
//                    }
//                }
//            }
//
//            if (state.getLastGoodChangeSetCommitTimeInIndex() > 0)
//            {
//                if (state.getLastIndexedChangeSetIdBeforeHoles() == -1)
//                {
//                    state.setLastIndexedChangeSetIdBeforeHoles(getStoredLongByLongTerm(reader, FIELD_ACLTXCOMMITTIME, FIELD_ACLTXID, state.getLastGoodTxCommitTimeInIndex()));
//                }
//                else
//                {
//                    long currentBestFromIndex = getStoredLongByLongTerm(reader, FIELD_ACLTXCOMMITTIME, FIELD_ACLTXID, state.getLastGoodTxCommitTimeInIndex());
//                    if (currentBestFromIndex > state.getLastIndexedTxIdBeforeHoles())
//                    {
//                        state.setLastIndexedChangeSetIdBeforeHoles(currentBestFromIndex);
//                    }
//                }
//            }
//        }
//        finally
//        {
//            if (refCounted != null)
//            {
//                refCounted.decref();
//            }
//        }
//
//        // Sets the trackerState only after it has been fully initialized
//        this.trackerState = state;
//        
//        return this.trackerState;
//    }

//    /*
//     * Find the largest numeric long term value in the given index field
//     */
//    private long getLargestTermValue(IndexReader reader, String fieldName, long limit)
//    {
//        long largestValue = 0;
//        long value;
//        
//        for(AtomicReaderContext atomicReaderContext  : reader.leaves())
//        {
//            value = getLargestTermValue(atomicReaderContext.reader(), fieldName, limit);
//            if(value > largestValue)
//            {
//                largestValue = value;
//            }
//        }
//        return largestValue;
//      
//    }
//    
//    /*
//     * Find the last numeric long term value
//     * -1 indicates not found
//     */
//    private long getLargestTermValue(AtomicReader atomicReader, String fieldName, long limit)
//    {
//        long largestValue = -1;
//        try
//        {
//            Terms terms = atomicReader.terms(fieldName);
//            if(terms != null)
//            {
//                TermsEnum termEnum = terms.iterator(null);
//                BytesRef bytesRef;
//                while((bytesRef = termEnum.next()) != null)
//                {
//                    long value = NumericUtils.prefixCodedToLong(bytesRef);
//                    if(limit > -1)
//                    {
//                        if(value < limit)
//                        {
//                            if(value > largestValue)
//                            {
//                                largestValue = value;
//                            }
//                        }
//                        else
//                        {
//                            return largestValue;
//                        }
//                    }
//                    else
//                    {
//                        if(value > largestValue)
//                        {
//                            largestValue = value;
//                        }
//                    }
//
//                }
//            }
//        }
//        catch (IOException e1)
//        {
//            // do nothing
//        }
//        return largestValue;
//    }
//    
//
//    private long getStoredLongByLongTerm(IndexReader reader, String term, String fieldable, Long value) throws IOException
//    {
//        long storedValue = 0;
//        
//        for(AtomicReaderContext atomicReaderContext  : reader.leaves())
//        {
//            storedValue = getStoredLongByLongTerm(atomicReaderContext.reader(), term, fieldable, value);
//            if(storedValue > 0L)
//            {
//                return storedValue;
//            }
//        }
//        return storedValue;
//    }
//    
//    private long getStoredLongByLongTerm(AtomicReader atomicReader, String term, String fieldable, Long value) throws IOException
//    {
//        long storedLong = -1L;
//        if (value != -1L)
//        {
//            DocsEnum docsEnum = atomicReader.termDocsEnum(AlfrescoSolrDataModel.getLongTerm(term, value));
//            if(docsEnum != null)
//            {
//                int docId = -1;
//                while ((docId = docsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS)
//                {
//                    Document doc = atomicReader.document(docId);
//                    IndexableField field = doc.getField(fieldable);
//                    if (field != null)
//                    {
//                        long curentStoredLong = field.numericValue().longValue();
//                        if (curentStoredLong > storedLong)
//                        {
//                            storedLong = curentStoredLong;
//                        }
//                    }
//                }
//            }
//        }
//        return storedLong;
//    }
    
    
    @Override
    public long indexAcl(List<AclReaders> aclReaderList, boolean overwrite) throws IOException
    {
        long start = System.nanoTime();
        UpdateRequestProcessor processor = this.core.getUpdateProcessingChain(null).createProcessor(getLocalSolrQueryRequest(), new SolrQueryResponse());
        try
        {
            for (AclReaders aclReaders : aclReaderList)
            {
                AddUpdateCommand cmd = new AddUpdateCommand(getLocalSolrQueryRequest());
                cmd.overwrite = overwrite;
                SolrInputDocument input = new SolrInputDocument();
                input.addField("id", AlfrescoSolrDataModel.getAclDocumentId(aclReaders.getTenantDomain(), aclReaders.getId()));
                // TODO Do we need to get this right - the version stamp from the DB? 
                input.addField("_version_", "0");
                input.addField(FIELD_ACLID, aclReaders.getId());
                input.addField(FIELD_INACLTXID, aclReaders.getAclChangeSetId());
                String tenant = aclReaders.getTenantDomain();
                for (String reader : aclReaders.getReaders())
                {
                    reader = addTenantToAuthority(reader, tenant);
                    input.addField(FIELD_READER, reader);
                }
                for (String denied : aclReaders.getDenied())
                {
                    denied = addTenantToAuthority(denied, tenant);
                    input.addField(FIELD_DENIED, denied);
                }
                cmd.solrDoc = input;
                processor.processAdd(cmd);
                //cmd.doc = LegacySolrInformationServer.toDocument(cmd.getSolrInputDocument(), core.getSchema(),  dataModel);
                //core.getUpdateHandler().addDoc(cmd);
            }
        }
        finally
        {
            processor.finish();
        }

        long end = System.nanoTime();
        return (end - start);
    }
    
    /**
     * Adds tenant information to an authority, <strong>if required</strong>, such that jbloggs for tenant example.com
     * would become jbloggs@example.com
     * 
     * @param authority   The authority to mutate, if it matches certain types.
     * @param tenant      The tenant that will be added to the authority.
     * @return The new authority information
     */
    private String addTenantToAuthority(String authority, String tenant)
    {
        switch (AuthorityType.getAuthorityType(authority))
        {
            case GROUP:
            case EVERYONE:
            case GUEST:
                if (tenant.length() == 0)
                {
                    // Default tenant matches 4.0
                }
                else
                {
                    authority = authority + "@" + tenant;
                }
                break;
            default:
                break;
        }
        return authority;
    }
    
    private LocalSolrQueryRequest getLocalSolrQueryRequest()
    {
        LocalSolrQueryRequest req = new LocalSolrQueryRequest(core, new NamedList<>());
        return req;
    }

    @Override
    public void indexAclTransaction(AclChangeSet changeSet, boolean overwrite) throws IOException
    {
        UpdateRequestProcessor processor = this.core.getUpdateProcessingChain(null).createProcessor(getLocalSolrQueryRequest(), new SolrQueryResponse());
        try
        {
            AddUpdateCommand cmd = new AddUpdateCommand(getLocalSolrQueryRequest());
            cmd.overwrite = overwrite;
            SolrInputDocument input = new SolrInputDocument();
            //input.addField(FIELD_ID, "ACLTX-" + changeSet.getId());
            input.addField("id", AlfrescoSolrDataModel.getAclChangeSetDocumentId(changeSet.getId()));
            input.addField("_version_", "0");
            input.addField(FIELD_ACLTXID, changeSet.getId());
            input.addField(FIELD_INACLTXID, changeSet.getId());
            input.addField(FIELD_ACLTXCOMMITTIME, changeSet.getCommitTimeMs());
            cmd.solrDoc = input;
            //cmd.doc = toDocument(cmd.getSolrInputDocument(), core.getSchema(), dataModel);
            processor.processAdd(cmd);
            putAclTransactionState(processor, changeSet);
        }
        finally
        {
            processor.finish();
        }
    }
    
    public void putAclTransactionState(UpdateRequestProcessor processor, AclChangeSet changeSet) throws IOException
    {
        AddUpdateCommand cmd = new AddUpdateCommand(getLocalSolrQueryRequest());
        cmd.overwrite = true;
        SolrInputDocument input = new SolrInputDocument();
        input.addField("id", "TRACKER!STATE!ACLTX");
        input.addField("_version_", "0");
        input.addField("S_ACLTXID", changeSet.getId());
        input.addField("S_INACLTXID", changeSet.getId());
        input.addField("S_ACLTXCOMMITTIME", changeSet.getCommitTimeMs());
        cmd.solrDoc = input;
        processor.processAdd(cmd);
    }

    @Override
    public void indexNode(Node node, boolean overwrite) throws IOException, AuthenticationException, JSONException
    {
        UpdateRequestProcessor processor = this.core.getUpdateProcessingChain(null).createProcessor(getLocalSolrQueryRequest(), new SolrQueryResponse());
        try
        {
            long start = System.nanoTime();

            //refCounted = core.getSearcher(false, true, null);
            //SolrIndexSearcher solrIndexSearcher = refCounted.get();

            if ((node.getStatus() == SolrApiNodeStatus.DELETED) || (node.getStatus() == SolrApiNodeStatus.UNKNOWN))
            {
                // fix up any secondary paths
                NodeMetaDataParameters nmdp = new NodeMetaDataParameters();
                nmdp.setFromNodeId(node.getId());
                nmdp.setToNodeId(node.getId());
                List<NodeMetaData> nodeMetaDatas;
                if (node.getStatus() == SolrApiNodeStatus.DELETED)
                {
                    // Fake the empty node metadata for this parent deleted node
                    NodeMetaData nodeMetaData = createDeletedNodeMetaData(node);
                    nodeMetaDatas = Collections.singletonList(nodeMetaData);
                }
                else
                {
                    nodeMetaDatas =  repositoryClient.getNodesMetaData(nmdp, Integer.MAX_VALUE);
                }
                
                NodeMetaData nodeMetaData = null;
                if (!nodeMetaDatas.isEmpty())
                {
                    nodeMetaData = nodeMetaDatas.get(0);
                    if (!(nodeMetaData.getTxnId() > node.getTxnId()))
                    {
                        LinkedHashSet<Long> visited = new LinkedHashSet<Long>();
                        //TODO: updateDescendantDocs(nodeMetaData, overwrite, solrIndexSearcher, visited, solrIndexSearcher.getDocSet(skippingDocsQuery));
                    }
                    // else, the node has moved on to a later transaction, and it will be indexed later
                }

                log.debug(".. deleting");
                if (nodeMetaData != null)
                {
                    this.removeDocFromContentStore(nodeMetaData);
                }
                deleteNode(processor, node);
            }

            if ((node.getStatus() == SolrApiNodeStatus.UPDATED) || (node.getStatus() == SolrApiNodeStatus.UNKNOWN))
            {
                log.info(".. updating");
                NodeMetaDataParameters nmdp = new NodeMetaDataParameters();
                nmdp.setFromNodeId(node.getId());
                nmdp.setToNodeId(node.getId());

                List<NodeMetaData> nodeMetaDatas =  repositoryClient.getNodesMetaData(nmdp, Integer.MAX_VALUE);

                AddUpdateCommand addDocCmd = new AddUpdateCommand(getLocalSolrQueryRequest());
                addDocCmd.overwrite = overwrite;

                if (!nodeMetaDatas.isEmpty())
                {
                    NodeMetaData nodeMetaData = nodeMetaDatas.get(0);
                    if (!(nodeMetaData.getTxnId() > node.getTxnId()))
                    {
                        if (mayHaveChildren(nodeMetaData))
                        {
                            cascadeUpdate(nodeMetaData);
                        }
                    }
                    // else, the node has moved on to a later transaction, and it will be indexed later
                    
                    // check index control
                    Map<QName, PropertyValue> properties = nodeMetaData.getProperties();
                    StringPropertyValue pValue = (StringPropertyValue) properties.get(ContentModel.PROP_IS_INDEXED);
                    if (pValue != null)
                    {
                        Boolean isIndexed = Boolean.valueOf(pValue.getValue());
                        if (!isIndexed.booleanValue())
                        {
                            log.debug(".. clearing unindexed");
                            deleteNode(processor, node);

                            SolrInputDocument doc = createNewDoc(nodeMetaData);
                            storeDocOnSolrContentStore(nodeMetaData, doc);
                            addDocCmd.solrDoc = doc;
                            processor.processAdd(addDocCmd);
                            //core.getUpdateHandler().addDoc(addDocCmd);

                            long end = System.nanoTime();
                            this.trackerStats.addNodeTime(end - start);
                            return;
                        }
                    }

                    // Make sure any unindexed or error doc is removed.
                    log.debug(".. deleting");
                    deleteNode(processor, node);
                    
                    SolrInputDocument doc = createNewDoc(nodeMetaData);
                    addToNewDocAndCache(nodeMetaData, doc);
                    addDocCmd.solrDoc = doc;
                    processor.processAdd(addDocCmd);
                    //core.getUpdateHandler().addDoc(addDocCmd);
                } // Ends checking for a nodeMetaData
            } // Ends checking for updated or unknown node status
            long end = System.nanoTime();
            this.trackerStats.addNodeTime(end - start);
        }
        catch (Exception e)
        {
            // generic recovery
            // Add failed node marker to try later
            // TODO: add to reporting
            // TODO: Store exception for display via query
            // TODO: retry failed

            log.debug(".. deleting on exception");
            deleteNode(processor, node);

            AddUpdateCommand addDocCmd = new AddUpdateCommand(getLocalSolrQueryRequest());
            addDocCmd.overwrite = overwrite;

            SolrInputDocument doc = new SolrInputDocument();
            // TODO: Error doc
            doc.addField("id", "ERROR-" + node.getId());
            doc.addField("_version_", "0");
            doc.addField(FIELD_DBID, node.getId());
            doc.addField(FIELD_INTXID, node.getTxnId());
            doc.addField(FIELD_EXCEPTION_MESSAGE, e.getMessage());

            StringWriter stringWriter = new StringWriter(4096);
            PrintWriter printWriter = new PrintWriter(stringWriter, true);
            try
            {
                e.printStackTrace(printWriter);
                String stack = stringWriter.toString();
                doc.addField(FIELD_EXCEPTION_STACK, stack.length() < 32766 ? stack : stack.substring(0, 32765));
            }
            finally
            {
                printWriter.close();
            }

            addDocCmd.solrDoc = doc;
            processor.processAdd(addDocCmd);
            //core.getUpdateHandler().addDoc(addDocCmd);
            
            log.warn("Node index failed and skipped for " + node.getId() + " in Tx " + node.getTxnId(), e);
        }
        finally
        {
            processor.finish();
        }
    }

    /**
     * Checks if a cascade update is necessary, and then updates descendants
     * @param nodeMetaData the metadata for the current node
     */
    private void cascadeUpdate(NodeMetaData nodeMetaData)
    {
        log.info(".. checking for path change");

        String query = FIELD_DBID + ":" + nodeMetaData.getId() + " AND " + FIELD_PARENT_ASSOC_CRC + ":"
                    + nodeMetaData.getParentAssocsCrc();
        boolean nodeHasSamePathAsBefore = cloudSelectReturnsDoc(query);
        if (nodeHasSamePathAsBefore)
        {
            log.debug("... found match");
        }
        else
        {
            query = FIELD_DBID + ":" + nodeMetaData.getId();
            boolean nodeHasBeenIndexed = cloudSelectReturnsDoc(query);
            if (nodeHasBeenIndexed)
            {
                log.debug("... cascade updating docs");
                LinkedHashSet<Long> visited = new LinkedHashSet<Long>();
                // TODO: updateDescendantAuxDocs(nodeMetaData, overwrite, solrIndexSearcher, visited,
                // solrIndexSearcher.getDocSet(skippingDocsQuery));
            }
            else
            {
                log.debug("... no doc to update");
            }
        }
    }

    private NodeMetaData createDeletedNodeMetaData(Node node)
    {
        NodeMetaData nodeMetaData = new NodeMetaData();
        nodeMetaData.setId(node.getId());
        nodeMetaData.setType(ContentModel.TYPE_DELETED);
        nodeMetaData.setNodeRef(new NodeRef(node.getNodeRef()));
        nodeMetaData.setTxnId(node.getTxnId());
        return nodeMetaData;
    }

    private SolrInputDocument createNewDoc(NodeMetaData nodeMetaData)
    {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", AlfrescoSolrDataModel.getNodeDocumentId(nodeMetaData.getTenantDomain(), 
                    nodeMetaData.getAclId(), nodeMetaData.getId()));
        doc.addField("_version_", 0);
        doc.addField(FIELD_DBID, nodeMetaData.getId());
        doc.addField(FIELD_LID, nodeMetaData.getNodeRef());
        doc.addField(FIELD_INTXID, nodeMetaData.getTxnId());
        return doc;
    }
    

    @Override
    public void indexNodes(List<Node> nodes, boolean overwrite) throws IOException, AuthenticationException, JSONException
    {
        UpdateRequestProcessor processor = this.core.getUpdateProcessingChain(null).createProcessor(getLocalSolrQueryRequest(), new SolrQueryResponse());
        try
        {
            long start = System.nanoTime();

            //refCounted = core.getSearcher(false, true, null);
            //SolrIndexSearcher solrIndexSearcher = refCounted.get();
            
            Map<Long, Node> nodeIdsToNodes = new HashMap<>();
            EnumMap<SolrApiNodeStatus, List<Long>> nodeStatusToNodeIds = new EnumMap<SolrApiNodeStatus, List<Long>>(SolrApiNodeStatus.class);
            categorizeNodes(nodes, nodeIdsToNodes, nodeStatusToNodeIds);
            List<Long> deletedNodeIds = mapNullToEmptyList(nodeStatusToNodeIds.get(SolrApiNodeStatus.DELETED));
            List<Long> unknownNodeIds = mapNullToEmptyList(nodeStatusToNodeIds.get(SolrApiNodeStatus.UNKNOWN));
            List<Long> updatedNodeIds = mapNullToEmptyList(nodeStatusToNodeIds.get(SolrApiNodeStatus.UPDATED));
            
            if (!deletedNodeIds.isEmpty() || !unknownNodeIds.isEmpty()) 
            {
                // fix up any secondary paths
                List<NodeMetaData> nodeMetaDatas = new ArrayList<>();
                
                // For all deleted nodes, fake the node metadata
                for (Long deletedNodeId : deletedNodeIds)
                {
                    Node node = nodeIdsToNodes.get(deletedNodeId);
                    NodeMetaData nodeMetaData = createDeletedNodeMetaData(node);
                    nodeMetaDatas.add(nodeMetaData);
                }
                
                if (!unknownNodeIds.isEmpty())
                {
                    NodeMetaDataParameters nmdp = new NodeMetaDataParameters();
                    nmdp.setNodeIds(unknownNodeIds);
                    nodeMetaDatas.addAll(repositoryClient.getNodesMetaData(nmdp, Integer.MAX_VALUE));
                }
                
                for (NodeMetaData nodeMetaData : nodeMetaDatas)
                {
                    Node node = nodeIdsToNodes.get(nodeMetaData.getId());
                    if (nodeMetaData.getTxnId() > node.getTxnId())
                    {
                        // the node has moved on to a later transaction
                        // it will be indexed later
                        continue;
                    }
                    LinkedHashSet<Long> visited = new LinkedHashSet<Long>();
                    //TODO: updateDescendantDocs(nodeMetaData, overwrite, solrIndexSearcher, visited, solrIndexSearcher.getDocSet(skippingDocsQuery));
                }

                log.debug(".. deleting");
                DeleteUpdateCommand delDocCmd = new DeleteUpdateCommand(getLocalSolrQueryRequest());
                @SuppressWarnings("unchecked")
                String query = this.getNodeIdQuery(FIELD_DBID, OR, deletedNodeIds, unknownNodeIds);
                delDocCmd.setQuery(query);
                processor.processDelete(delDocCmd);
                //core.getUpdateHandler().deleteByQuery(delDocCmd);
            }

            if (!updatedNodeIds.isEmpty() || !unknownNodeIds.isEmpty()) 
            {
                log.info(".. updating");
                NodeMetaDataParameters nmdp = new NodeMetaDataParameters();
                List<Long> nodeIds = new LinkedList<>();
                nodeIds.addAll(updatedNodeIds);
                nodeIds.addAll(unknownNodeIds);
                nmdp.setNodeIds(nodeIds);

                // Fetches bulk metadata
                List<NodeMetaData> nodeMetaDatas =  repositoryClient.getNodesMetaData(nmdp, Integer.MAX_VALUE);

                for (NodeMetaData nodeMetaData : nodeMetaDatas)
                {
                    AddUpdateCommand addDocCmd = new AddUpdateCommand(getLocalSolrQueryRequest());
                    addDocCmd.overwrite = overwrite;
                    
                    Node node = nodeIdsToNodes.get(nodeMetaData.getId());
                    if (nodeMetaData.getTxnId() > node.getTxnId())
                    {
                        // the node has moved on to a later transaction
                        // it will be indexed later
                        continue;
                    }

                    if (mayHaveChildren(nodeMetaData))
                    {
                        cascadeUpdate(nodeMetaData);
                    }
                    
                    // check index control
                    Map<QName, PropertyValue> properties = nodeMetaData.getProperties();
                    StringPropertyValue pValue = (StringPropertyValue) properties.get(ContentModel.PROP_IS_INDEXED);
                    if (pValue != null)
                    {
                        Boolean isIndexed = Boolean.valueOf(pValue.getValue());
                        if (!isIndexed.booleanValue())
                        {
                            log.debug(".. clearing unindexed");
                            deleteNode(processor, node);

                            SolrInputDocument doc = createNewDoc(nodeMetaData);
                            storeDocOnSolrContentStore(nodeMetaData, doc);
                            addDocCmd.solrDoc = doc;
                            processor.processAdd(addDocCmd);
                            //core.getUpdateHandler().addDoc(addDocCmd);
                        }
                    }
                    
                    // Make sure any unindexed or error doc is removed.
                    log.debug(".. deleting node " + node.getId());
                    deleteNode(processor, node);
                    
                    SolrInputDocument doc = createNewDoc(nodeMetaData);
                    addToNewDocAndCache(nodeMetaData, doc);
                    addDocCmd.solrDoc = doc;
                    processor.processAdd(addDocCmd);
                    //core.getUpdateHandler().addDoc(addDocCmd);
                } // Ends iteration over nodeMetadatas

            } // Ends checking for the existence of updated or unknown node ids 
            long end = System.nanoTime();
            this.trackerStats.addNodeTime(end - start);
        }
        catch (Exception e)
        {
            // Bulk version failed, so do one at a time.
            for (Node node : nodes)
            {
                this.indexNode(node, true);
            }
        }
        finally
        {
            processor.finish();
        }
      
    }

    private void addToNewDocAndCache(NodeMetaData nodeMetaData, SolrInputDocument newDoc) throws IOException,
                AuthenticationException
    {
        addFieldsToDoc(nodeMetaData, newDoc);
        SolrInputDocument cachedDoc = null;
        boolean isContentIndexedForNode = isContentIndexedForNode(nodeMetaData.getProperties());
        String fixedTenantDomain = AlfrescoSolrDataModel.getTenantId(nodeMetaData.getTenantDomain());
        if (isContentIndexedForNode)
        {
            cachedDoc = retrieveDocFromSolrContentStore(fixedTenantDomain, nodeMetaData.getId());
        }
        addPropertiesToDoc(nodeMetaData, isContentIndexedForNode, newDoc, cachedDoc);
        if (isContentIndexedForNode)
        {
            // Now that the new doc is fully updated and is about to go to the Solr index, cache it.
            storeDocOnSolrContentStore(fixedTenantDomain, nodeMetaData.getId(), newDoc);
        }
    }

    private void addFieldsToDoc(NodeMetaData nodeMetaData, SolrInputDocument doc)
    {
        doc.addField(FIELD_TYPE, nodeMetaData.getType().toString());
        for (QName aspect : nodeMetaData.getAspects())
        {
            doc.addField(FIELD_ASPECT, aspect.toString());
        }
        doc.addField(FIELD_ISNODE, "T");
        // FIELD_FTSSTATUS is set when adding content properties to indicate whether or not the cache is clean.
               
        doc.addField(FIELD_TENANT, AlfrescoSolrDataModel.getTenantId(nodeMetaData.getTenantDomain()));

        for (Pair<String, QName> path : nodeMetaData.getPaths())
        {
            doc.addField(FIELD_PATH, path.getFirst());
        }

        if (nodeMetaData.getOwner() != null)
        {
            doc.addField(FIELD_OWNER, nodeMetaData.getOwner());
        }
        doc.addField(FIELD_PARENT_ASSOC_CRC, nodeMetaData.getParentAssocsCrc());

        StringBuilder qNameBuffer = new StringBuilder(64);
        StringBuilder assocTypeQNameBuffer = new StringBuilder(64);
        if (nodeMetaData.getParentAssocs() != null)
        {
            for (ChildAssociationRef childAssocRef : nodeMetaData.getParentAssocs())
            {
                if (qNameBuffer.length() > 0)
                {
                    qNameBuffer.append(";/");
                    assocTypeQNameBuffer.append(";/");
                }
                qNameBuffer.append(ISO9075.getXPathName(childAssocRef.getQName()));
                assocTypeQNameBuffer.append(ISO9075.getXPathName(childAssocRef.getTypeQName()));
                doc.addField(FIELD_PARENT, childAssocRef.getParentRef());

                if (childAssocRef.isPrimary())
                {
                    doc.addField(FIELD_PRIMARYPARENT, childAssocRef.getParentRef());
                    doc.addField(FIELD_PRIMARYASSOCTYPEQNAME,
                                ISO9075.getXPathName(childAssocRef.getTypeQName()));
                    doc.addField(FIELD_PRIMARYASSOCQNAME, ISO9075.getXPathName(childAssocRef.getQName()));

                }
            }
            doc.addField(FIELD_ASSOCTYPEQNAME, assocTypeQNameBuffer.toString());
            doc.addField(FIELD_QNAME, qNameBuffer.toString());
        }
        if (nodeMetaData.getAncestors() != null)
        {
            for (NodeRef ancestor : nodeMetaData.getAncestors())
            {
                doc.addField(FIELD_ANCESTOR, ancestor.toString());
            }
        }
    }

    private void addPropertiesToDoc(NodeMetaData nodeMetaData, boolean isContentIndexedForNode, 
                SolrInputDocument newDoc, SolrInputDocument cachedDoc) 
                throws IOException, AuthenticationException
    {
        Map<QName, PropertyValue> properties = nodeMetaData.getProperties();
        for (QName propertyQName : properties.keySet())
        {
            PropertyValue value = properties.get(propertyQName);
            if(value != null)
            {
                if (value instanceof StringPropertyValue)
                {
                    for( FieldInstance  field : AlfrescoSolrDataModel.getInstance().getIndexedFieldNamesForProperty(propertyQName).getFields())
                    {
                        addStringPropertyToDoc(newDoc, field, (StringPropertyValue) value, properties);
                    }
                }
                else if (value instanceof MLTextPropertyValue)
                {
                    for( FieldInstance  field : AlfrescoSolrDataModel.getInstance().getIndexedFieldNamesForProperty(propertyQName).getFields())
                    {
                        addMLTextPropertyToDoc(newDoc, field, (MLTextPropertyValue) value);
                    }

                }
                else if (value instanceof ContentPropertyValue)
                {
                    if (isContentIndexedForNode)
                    {
                        addContentPropertyToDocUsingCache(newDoc, cachedDoc, propertyQName, (ContentPropertyValue) value);
                    }
                }
                else if (value instanceof MultiPropertyValue)
                {
                    MultiPropertyValue typedValue = (MultiPropertyValue) value;
                    for (PropertyValue singleValue : typedValue.getValues())
                    {
                        if (singleValue instanceof StringPropertyValue)
                        {
                            for (FieldInstance field : AlfrescoSolrDataModel.getInstance()
                                        .getIndexedFieldNamesForProperty(propertyQName).getFields())
                            {
                                addStringPropertyToDoc(newDoc, field, (StringPropertyValue) singleValue, properties);
                            }
                        }
                        else if (singleValue instanceof MLTextPropertyValue)
                        {
                            for (FieldInstance field : AlfrescoSolrDataModel.getInstance()
                                        .getIndexedFieldNamesForProperty(propertyQName).getFields())
                            {
                                addMLTextPropertyToDoc(newDoc, field, (MLTextPropertyValue) singleValue);
                            }

                        }
                        else if (singleValue instanceof ContentPropertyValue)
                        {
                            if (isContentIndexedForNode)
                            {
                                addContentPropertyToDocUsingCache(newDoc, cachedDoc, propertyQName,
                                            (ContentPropertyValue) singleValue);
                            }
                        }
                    }
                }
            }
        }
    }

    private void deleteNode(UpdateRequestProcessor processor, Node node) throws IOException
    {
        DeleteUpdateCommand delDocCmd = new DeleteUpdateCommand(getLocalSolrQueryRequest());
        delDocCmd.setQuery(FIELD_DBID + ":" + node.getId());
        processor.processDelete(delDocCmd);
        //core.getUpdateHandler().deleteByQuery(delDocCmd);
    }

    private boolean isContentIndexedForNode(Map<QName, PropertyValue> properties)
    {
        boolean isContentIndexed = true;
        if (properties.containsKey(ContentModel.PROP_IS_CONTENT_INDEXED))
        {
            StringPropertyValue pValue = (StringPropertyValue) properties
                        .get(ContentModel.PROP_IS_CONTENT_INDEXED);
            if (pValue != null)
            {
                Boolean isIndexed = Boolean.valueOf(pValue.getValue());
                if ((isIndexed != null) && (isIndexed.booleanValue() == false))
                {
                    isContentIndexed = false;
                }
            }
        }
        return isContentIndexed;
    }
    
    @SuppressWarnings("unchecked")
    private String getNodeIdQuery(String fieldName, String operator, Collection<Long> ... nodeIdLists) 
    {
        StringBuilder query = new StringBuilder();
        for (Collection<Long> nodeIdList : nodeIdLists)
        {
            for (Long nodeId : nodeIdList)
            {
                query.append(fieldName).append(":").append(nodeId).append(operator);
            }
        }
        query.delete(query.length() - 1 - operator.length(), query.length());
        
        return query.toString();
    }
    
    /**
     * @param list
     * @return
     */
    private List<Long> mapNullToEmptyList(List<Long> list)
    {
        return list == null ? Collections.<Long>emptyList() : list;
    }

    private void categorizeNodes(List<Node> nodes, Map<Long, Node> nodeIdsToNodes,
                EnumMap<SolrApiNodeStatus, List<Long>> nodeStatusToNodeIds)
    {
        for (Node node : nodes)
        {
            nodeIdsToNodes.put(node.getId(), node);
            
            List<Long> nodeIds = nodeStatusToNodeIds.get(node.getStatus());
            if (nodeIds == null)
            {
                nodeIds = new LinkedList<>();
                nodeStatusToNodeIds.put(node.getStatus(), nodeIds);
            }
            nodeIds.add(node.getId());
        }
    }

    /**
     * Gets the field name used in Solr for the specified content property.
     * Assumes that the first defined field in Solr is the "right one".
     * @param propertyQName the content property qualified name
     * @param type the content property field type, i.e. DOCID
     * @return a String representing the name of the field in Solr or null if not found
     */
    private String getSolrFieldNameForContentPropertyMetadata(QName propertyQName, AlfrescoSolrDataModel.ContentFieldType type)
    {
        IndexedField indexedField = AlfrescoSolrDataModel.getInstance().getIndexedFieldForContentPropertyMetadata(propertyQName, type);
        List<FieldInstance> fields = indexedField.getFields();
        String fieldName = null;
        if (fields != null && !fields.isEmpty())
        {
            FieldInstance instance = fields.get(0);
            if (instance != null)
            {
                fieldName = instance.getField();
            }
        }
        return fieldName;
    }


    private void addContentPropertyMetadata(SolrInputDocument doc, QName propertyQName, 
                AlfrescoSolrDataModel.ContentFieldType type, GetTextContentResponse textContentResponse)
    {
        IndexedField indexedField = AlfrescoSolrDataModel.getInstance().getIndexedFieldForContentPropertyMetadata(
                    propertyQName, type);
        for (FieldInstance fieldInstance : indexedField.getFields())
        {
            switch(type)
            {
            case TRANSFORMATION_EXCEPTION:
                doc.addField(fieldInstance.getField(), textContentResponse.getTransformException());
                break;
            case TRANSFORMATION_STATUS:
                doc.addField(fieldInstance.getField(), textContentResponse.getStatus());
                break;
            case TRANSFORMATION_TIME:
                doc.addField(fieldInstance.getField(), textContentResponse.getTransformDuration());
                break;
                // Skips the ones that require the ContentPropertyValue
                default:
                break;
            }
        }
    }

    private void addContentPropertyMetadata(SolrInputDocument doc, QName propertyQName, 
                ContentPropertyValue contentPropertyValue, AlfrescoSolrDataModel.ContentFieldType type)
    {
        IndexedField indexedField = AlfrescoSolrDataModel.getInstance().getIndexedFieldForContentPropertyMetadata(
                    propertyQName, type);
        for (FieldInstance fieldInstance : indexedField.getFields())
        {
            switch(type)
            {
            case DOCID:
                doc.addField(fieldInstance.getField(), contentPropertyValue.getId());
                break;
            case ENCODING:
                doc.addField(fieldInstance.getField(), contentPropertyValue.getEncoding());
                break;
            case LOCALE:
                doc.addField(fieldInstance.getField(), contentPropertyValue.getLocale());
                break;
            case MIMETYPE:
                doc.addField(fieldInstance.getField(), contentPropertyValue.getMimetype());
                break;
            case SIZE:
                doc.addField(fieldInstance.getField(), contentPropertyValue.getLength());
                break;
                // Skips the ones that require the text content response
                default:
                break;
            }
        }
    }
    
    
    private void addContentPropertyToDocUsingCache(SolrInputDocument newDoc, SolrInputDocument cachedDoc, 
                QName propertyQName, ContentPropertyValue contentPropertyValue)
                    throws AuthenticationException, IOException
    {
        addContentPropertyMetadata(newDoc, propertyQName, contentPropertyValue, AlfrescoSolrDataModel.ContentFieldType.DOCID);
        addContentPropertyMetadata(newDoc, propertyQName, contentPropertyValue, AlfrescoSolrDataModel.ContentFieldType.SIZE);
        addContentPropertyMetadata(newDoc, propertyQName, contentPropertyValue, AlfrescoSolrDataModel.ContentFieldType.LOCALE);
        addContentPropertyMetadata(newDoc, propertyQName, contentPropertyValue, AlfrescoSolrDataModel.ContentFieldType.MIMETYPE);
        addContentPropertyMetadata(newDoc, propertyQName, contentPropertyValue, AlfrescoSolrDataModel.ContentFieldType.ENCODING);
        
        if (false == transformContent) 
        {
            // Marks it as Clean so we do not get the actual content
            markFTSStatus(newDoc,  FTSStatus.Clean);
            return;
        }
        
        if (cachedDoc != null)
        {
            // Builds up the new solr doc from the cached content regardless of whether or not it is current
            List<FieldInstance> fields = AlfrescoSolrDataModel.getInstance().getIndexedFieldNamesForProperty(
                        propertyQName).getFields();
            for (FieldInstance  field : fields)
            {
                String fieldName = field.getField();
                Object cachedFieldValue = cachedDoc.getFieldValue(fieldName);
                newDoc.addField(fieldName, cachedFieldValue);
            }

            String transformationStatusFieldName = getSolrFieldNameForContentPropertyMetadata(propertyQName, 
                        AlfrescoSolrDataModel.ContentFieldType.TRANSFORMATION_STATUS);
            newDoc.addField(transformationStatusFieldName, cachedDoc.getFieldValue(transformationStatusFieldName));
            String transformationExceptionFieldName = getSolrFieldNameForContentPropertyMetadata(propertyQName, 
                        AlfrescoSolrDataModel.ContentFieldType.TRANSFORMATION_EXCEPTION);
            newDoc.addField(transformationExceptionFieldName, cachedDoc.getFieldValue(transformationExceptionFieldName));
            String transformationTimeFieldName = getSolrFieldNameForContentPropertyMetadata(propertyQName, 
                        AlfrescoSolrDataModel.ContentFieldType.TRANSFORMATION_TIME);
            newDoc.addField(transformationTimeFieldName, cachedDoc.getFieldValue(transformationTimeFieldName));

            // Gets the new content docid and compares to that of the cachedDoc to mark the content as clean/dirty
            String fldName = getSolrFieldNameForContentPropertyMetadata(propertyQName, 
                        AlfrescoSolrDataModel.ContentFieldType.DOCID);
            long cachedDocContentDocid = Long.valueOf(String.valueOf(cachedDoc.getFieldValue(fldName)));
            long currentContentDocid = contentPropertyValue.getId();
            if (cachedDocContentDocid == currentContentDocid)
            {
                // The content in the cache is current
                markFTSStatus(newDoc, FTSStatus.Clean);
            }
            else
            {
                // The cached content is out of date
                markFTSStatus(newDoc, FTSStatus.Dirty);
            }
        }
        else 
        {
            // There is not a SolrInputDocument in the solrContentStore, so no content is added now to the new solr doc
            markFTSStatus(newDoc, FTSStatus.New);
        }
    }
    
    @Override
    public void updateContentToIndexAndCache(long dbId, String tenant) throws Exception
    {
        SolrInputDocument cachedDoc = retrieveDocFromSolrContentStore(tenant, dbId);
        if (cachedDoc != null)
        {
            addContentToCachedDoc(cachedDoc, dbId);
            // Marks as clean since the doc's content is now up to date
            markFTSStatus(cachedDoc, FTSStatus.Clean);
            storeDocOnSolrContentStore(tenant, dbId, cachedDoc);
            
            // Add to index
            AddUpdateCommand addDocCmd = new AddUpdateCommand(getLocalSolrQueryRequest());
            addDocCmd.overwrite = true;
            addDocCmd.solrDoc = cachedDoc;
            UpdateRequestProcessor processor = this.core.getUpdateProcessingChain(null).createProcessor(
                        getLocalSolrQueryRequest(), new SolrQueryResponse());
            processor.processAdd(addDocCmd);
        }
        else
        {
            throw new Exception("This method should not be called unless there is a cached doc in the content store.");
        }
    }

    private void markFTSStatus(SolrInputDocument doc, FTSStatus status)
    {
        doc.removeField(FIELD_FTSSTATUS);
        doc.addField(FIELD_FTSSTATUS, status.toString());
    }
    
    private void addContentToCachedDoc(SolrInputDocument cachedDoc, long dbId) throws UnsupportedEncodingException, AuthenticationException, IOException
    {
        for (String fieldName : cachedDoc.deepCopy().getFieldNames())
        {
            if (fieldName.startsWith(AlfrescoSolrDataModel.CONTENT_LOCALE_PREFIX))
            {
                String locale = String.valueOf(cachedDoc.getFieldValue(fieldName));
                String qNamePart = fieldName.substring(AlfrescoSolrDataModel.CONTENT_LOCALE_PREFIX.length());
                QName propertyQName = QName.createQName(qNamePart);
                addContentPropertyToDocUsingAlfrescoRepository(cachedDoc, propertyQName, dbId, locale);
            }
        }
    }
    
    private void addContentPropertyToDocUsingAlfrescoRepository(SolrInputDocument cachedDoc, 
                QName propertyQName, long dbId, String locale) 
                            throws AuthenticationException, IOException, UnsupportedEncodingException
    {
        long start = System.nanoTime();
        
        // Expensive call to be done with ContentTrakcer
        GetTextContentResponse response = repositoryClient.getTextContent(dbId, propertyQName, null);
        
        addContentPropertyMetadata(cachedDoc, propertyQName, AlfrescoSolrDataModel.ContentFieldType.TRANSFORMATION_STATUS, response);
        addContentPropertyMetadata(cachedDoc, propertyQName, AlfrescoSolrDataModel.ContentFieldType.TRANSFORMATION_EXCEPTION, response);
        addContentPropertyMetadata(cachedDoc, propertyQName, AlfrescoSolrDataModel.ContentFieldType.TRANSFORMATION_TIME, response);

        InputStream ris = response.getContent();
        String textContent = "";
        try
        {
            if (ris != null)
            {
                // Get and copy content
                byte[] bytes = FileCopyUtils.copyToByteArray(ris);
                textContent = new String( bytes, "UTF8");
            }
        }
        finally
        {
            // release the response only when the content has been read
            response.release();
        }
        
        long end = System.nanoTime();
        this.getTrackerStats().addDocTransformationTime(end - start);
        
        StringBuilder builder = new StringBuilder();
        builder.append("\u0000").append(locale).append("\u0000");
        builder.append(textContent);

        for (FieldInstance  field : AlfrescoSolrDataModel.getInstance().getIndexedFieldNamesForProperty(propertyQName).getFields())
        {
            if(field.isLocalised())
            {
                cachedDoc.addField(field.getField(), builder.toString());
            }
            else
            {
                cachedDoc.addField(field.getField(), textContent);
            }
        }
    }

    private void removeDocFromContentStore(NodeMetaData nodeMetaData)
    {
        String fixedTenantDomain = AlfrescoSolrDataModel.getTenantId(nodeMetaData.getTenantDomain());
        ContentContext contentContext = SolrContentUrlBuilder
                    .start()
                    .add(SolrContentUrlBuilder.KEY_TENANT, fixedTenantDomain)
                    .add(SolrContentUrlBuilder.KEY_DB_ID, String.valueOf(nodeMetaData.getId()))
                    .getContentContext();
        if (this.solrContentStore.exists(contentContext.getContentUrl()))
        {
            this.solrContentStore.delete(contentContext.getContentUrl());
        }
    }

    private void storeDocOnSolrContentStore(NodeMetaData nodeMetaData, SolrInputDocument doc) throws IOException
    {
        String fixedTenantDomain = AlfrescoSolrDataModel.getTenantId(nodeMetaData.getTenantDomain());
        storeDocOnSolrContentStore(fixedTenantDomain, nodeMetaData.getId(), doc);
    }
    
    private void storeDocOnSolrContentStore(String tenant, long dbId, SolrInputDocument doc) throws IOException
    {
        ContentContext contentContext = SolrContentUrlBuilder
                    .start()
                    .add(SolrContentUrlBuilder.KEY_TENANT, tenant)
                    .add(SolrContentUrlBuilder.KEY_DB_ID, String.valueOf(dbId))
                    .getContentContext();
        if (this.solrContentStore.exists(contentContext.getContentUrl()))
        {
            this.solrContentStore.delete(contentContext.getContentUrl());
        }
      
        ContentWriter writer = this.solrContentStore.getWriter(contentContext);
        try (
                    OutputStream contentOutputStream = writer.getContentOutputStream();
                    // Compresses the document
                    //GZIPOutputStream gzip = new GZIPOutputStream(contentOutputStream);
                    //FastOutputStream fos = new FastOutputStream(contentOutputStream);
            )
        {
            JavaBinCodec codec = new JavaBinCodec(resolver);
            codec.marshal(doc, contentOutputStream);
        }
    }

    private SolrInputDocument retrieveDocFromSolrContentStore(String tenant, long dbId) throws IOException
    {
        String contentUrl = SolrContentUrlBuilder
                    .start()
                    .add(SolrContentUrlBuilder.KEY_TENANT, tenant)
                    .add(SolrContentUrlBuilder.KEY_DB_ID, String.valueOf(dbId))
                    .get();
        if (!this.solrContentStore.exists(contentUrl))
        {
            return null;
        }
        ContentReader reader = this.solrContentStore.getReader(contentUrl);
        SolrInputDocument cachedDoc = null;
        if(reader.exists())
        {

            // try-with-resources statement closes all these InputStreams
            try (
                    InputStream contentInputStream = reader.getContentInputStream();
                    // Uncompresses the document
                    //GZIPInputStream gzip = new GZIPInputStream(contentInputStream);
                    //FastInputStream fis = new FastInputStream(contentInputStream)
                    )
                    { 
                cachedDoc = (SolrInputDocument) new JavaBinCodec(resolver).unmarshal(contentInputStream);
                    }
        }
        return cachedDoc;
    }
    
    

    private void addMLTextPropertyToDoc(SolrInputDocument doc, FieldInstance field, MLTextPropertyValue mlTextPropertyValue) throws IOException
    {   
        if(field.isLocalised())
        {
            StringBuilder sort = new StringBuilder();
            for (Locale locale : mlTextPropertyValue.getLocales())
            {
                if(log.isDebugEnabled())
                {
                    log.debug("ML "+field.getField() + " in "+ locale+ " of "+mlTextPropertyValue.getValue(locale));
                }
                
                StringBuilder builder = new StringBuilder();
                builder.append("\u0000").append(locale.toString()).append("\u0000")
                .append(mlTextPropertyValue.getValue(locale));
       
                if(!field.isSort())
                {
                    doc.addField(field.getField(), builder.toString());
                }
                
                if (sort.length() > 0)
                {
                    sort.append("\u0000");
                }
                sort.append(builder.toString());
            }
            if(field.isSort())
            {
                doc.addField(field.getField(), sort.toString());
            }
        }
        else
        {
            for (Locale locale : mlTextPropertyValue.getLocales())
            {
                doc.addField(field.getField(), mlTextPropertyValue.getValue(locale));
            }
        }

    }
    
    private void addStringPropertyToDoc(SolrInputDocument doc, FieldInstance field, StringPropertyValue stringPropertyValue, Map<QName, PropertyValue> properties) throws IOException
    {

        if(field.isLocalised())
        {
            Locale locale = null;

            PropertyValue localePropertyValue = properties.get(ContentModel.PROP_LOCALE);
            if (localePropertyValue != null)
            {
                locale = DefaultTypeConverter.INSTANCE.convert(Locale.class,
                        ((StringPropertyValue) localePropertyValue).getValue());
            }

            if (locale == null)
            {
                locale = I18NUtil.getLocale();
            }

            StringBuilder builder;
            builder = new StringBuilder();
            builder.append("\u0000").append(locale.toString()).append("\u0000")
            .append(stringPropertyValue.getValue());

            doc.addField(field.getField(), builder.toString());
        }
        else
        { 
            doc.addField(field.getField(), stringPropertyValue.getValue());
        }

    }
    
    private boolean mayHaveChildren(NodeMetaData nodeMetaData)
    {
        // 1) Does the type support children?
        TypeDefinition nodeTypeDef = dataModel
                    .getDictionaryService(CMISStrictDictionaryService.DEFAULT).getType(nodeMetaData.getType());
        if ((nodeTypeDef != null) && (nodeTypeDef.getChildAssociations().size() > 0)) { return true; }
        // 2) Do any of the applied aspects support children?
        for (QName aspect : nodeMetaData.getAspects())
        {
            AspectDefinition aspectDef = dataModel
                        .getDictionaryService(CMISStrictDictionaryService.DEFAULT).getAspect(aspect);
            if ((aspectDef != null) && (aspectDef.getChildAssociations().size() > 0)) { return true; }
        }
        return false;
    }
    

    @Override
    public void indexTransaction(Transaction info, boolean overwrite) throws IOException
    {
        UpdateRequestProcessor processor = this.core.getUpdateProcessingChain(null).createProcessor(getLocalSolrQueryRequest(), new SolrQueryResponse());
        try
        {
            AddUpdateCommand cmd = new AddUpdateCommand(getLocalSolrQueryRequest());
            cmd.overwrite = overwrite;
            SolrInputDocument input = new SolrInputDocument();
            input.addField("id", AlfrescoSolrDataModel.getTransactionDocumentId(info.getId()));
            input.addField("_version_", 0);
            input.addField(FIELD_TXID, info.getId());
            input.addField(FIELD_INTXID, info.getId());
            input.addField(FIELD_TXCOMMITTIME, info.getCommitTimeMs());
            cmd.solrDoc = input;
            processor.processAdd(cmd);
            //core.getUpdateHandler().addDoc(cmd);

            putTransactionState(processor, info);
        }
        finally
        {
            processor.finish();
        }
    }
    
    public void putTransactionState(UpdateRequestProcessor processor, Transaction info) throws IOException
    {
        AddUpdateCommand cmd = new AddUpdateCommand(getLocalSolrQueryRequest());
        cmd.overwrite = true;
        SolrInputDocument input = new SolrInputDocument();
        input.addField("id", "TRACKER!STATE!TX");
        input.addField("_version_", 0);
        input.addField("S_TXID", info.getId());
        input.addField("S_INTXID", info.getId());
        input.addField("S_TXCOMMITTIME", info.getCommitTimeMs());
        cmd.solrDoc = input;
        processor.processAdd(cmd);
        //core.getUpdateHandler().addDoc(cmd);
    }

    @Override
    public boolean isInIndex(String field, long id) throws IOException
    {
        SolrRequestHandler handler = core.getRequestHandler("/get");
        LocalSolrQueryRequest req = getLocalSolrQueryRequest();
        SolrQueryResponse rsp = new SolrQueryResponse();
        
        ModifiableSolrParams newParams = new ModifiableSolrParams(req.getParams());
        newParams.set("ids", field);
        req.setParams(newParams);
        
        handler.handleRequest(req, rsp);
        
        NamedList values = rsp.getValues();
        SolrDocumentList response = (SolrDocumentList)values.get("response");
        return response.getNumFound() > 0;
    }
    
    
//    @Override
//    public boolean isInIndexOld(String field, long id) throws IOException
//    {
//        RefCounted<SolrIndexSearcher> refCounted = null;
//        try
//        {
//            refCounted = core.getSearcher(false, true, null);
//            SolrIndexSearcher solrIndexSearcher = refCounted.get();
//            IndexReader reader = solrIndexSearcher.getIndexReader();
//            
//            return isInIndex(reader, field, id);
//        }
//        finally
//        {
//            if (refCounted != null)
//            {
//                refCounted.decref();
//            }
//        }
//            
//    }
    
//    private boolean isInIndex(IndexReader reader, String field, long value) throws IOException
//    {
//        for(AtomicReaderContext atomicReaderContext  : reader.leaves())
//        {
//            if(isInIndex(atomicReaderContext.reader(), field, value))
//            {
//                return true;
//            }
//        }
//        return false;
//    }
//    
//    private boolean isInIndex(AtomicReader atomicReader, String field, long value) throws IOException
//    {
//        if (value != -1L)
//        {
//            DocsEnum docsEnum = atomicReader.termDocsEnum(AlfrescoSolrDataModel.getLongTerm(field, value));
//            if(docsEnum != null)
//            {
//                if(docsEnum.nextDoc() != DocIdSetIterator.NO_MORE_DOCS)
//                {
//                    return true;
//                }
//            }
//        }
//        return false;
//    }

    @Override
    public boolean putModel(M2Model model)
    {
        return this.dataModel.putModel(model);
    }

    @Override
    public void rollback() throws IOException
    {
        UpdateRequestProcessor processor = this.core.getUpdateProcessingChain(null).createProcessor(getLocalSolrQueryRequest(), new SolrQueryResponse());
        try
        {
            processor.processRollback(new RollbackUpdateCommand(getLocalSolrQueryRequest()));
            //this.core.getUpdateHandler().rollback(new RollbackUpdateCommand(getLocalSolrQueryRequest()));
        }
        finally
        {
            processor.finish();
        }
        
    }
}
