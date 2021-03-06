package com.haulmont.addon.extftssearch.core;

import com.google.common.base.Strings;
import com.haulmont.fts.core.app.FtsServiceBean;
import com.haulmont.fts.core.sys.IndexSearcherProvider;
import com.haulmont.fts.core.sys.LuceneSearcher;
import com.haulmont.fts.global.EntityInfo;
import com.haulmont.fts.global.SearchResult;
import com.haulmont.fts.global.SearchResultEntry;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.haulmont.fts.core.sys.LuceneConstants.*;

/**
 * Extended service that allows to execute FTS searches with AND operation. Search terms may be spread out between the
 * main entity and the linked entities. For example a main entity may contain the search term and some of linked
 * entities may contain the second term
 */
public class ExtFtsServiceBean extends FtsServiceBean {

    private static final Logger log = LoggerFactory.getLogger(ExtFtsServiceBean.class);

    @Inject
    private IndexSearcherProvider indexSearcherProvider;

    //A regex that is used for extracting field values from the EntityInfo.text fields.
    //Text there is stored in strings like this: ^^fieldName fieldValue ^^fieldName fieldValue ^^fieldName fieldValue
    private static final Pattern FIELD_VALUE_PATTERN = Pattern.compile("\\^\\^(\\w+)\\s+([^\\^]+)");

    @Override
    public SearchResult search(String searchTerm, List<String> entityNames) {
        LuceneSearcher searcher = luceneSearcher;
        Map<UUID, EntitiesGraph> entitiesGraphsMap = new HashMap<>();

        //first search among entities with names from entityNames method parameter. The search is performed with the OR
        //condition, so the result will contain entities that have from 1 to N of passed search terms.
        List<EntityInfo> allFieldResults = luceneSearcher.searchAllField(searchTerm, entityNames);

        SearchResult searchResult = new SearchResult(searchTerm);
        for (EntityInfo entityInfo : allFieldResults) {
            EntitiesGraph entitiesGraph = entitiesGraphsMap.get(entityInfo.getId());
            if (entitiesGraph == null) {
                entitiesGraph = new EntitiesGraph(entityInfo);
                entitiesGraphsMap.put((UUID) entityInfo.getId(), entitiesGraph);
            }
        }

        //try to find entities that has a link to other entities (not from entityNames collection)
        //that match a search criteria. The search is performed with the OR
        //condition, so the result will contain entities that have from 1 to N of passed search terms.
        Set<String> linkedEntitiesNames = new HashSet<>();
        for (String entityName : entityNames) {
            linkedEntitiesNames.addAll(findLinkedEntitiesNames(entityName));
        }

        List<EntityInfo> linkedEntitiesInfos = luceneSearcher.searchAllField(searchTerm, linkedEntitiesNames);
        for (EntityInfo linkedEntitiesInfo : linkedEntitiesInfos) {
            List<EntityInfo> entitiesWithLinkInfos = luceneSearcher.searchLinksField(linkedEntitiesInfo.toString(), entityNames);
            //for backward compatibility. Previously "links" field of the Lucene document contained a set of linked entities ids.
            //Now a set of {@link EntityInfo} objects is stored there. We need to make a second search to find entities,
            //that were indexed before this modification.
            entitiesWithLinkInfos.addAll(searcher.searchLinksField(linkedEntitiesInfo.getId(), entityNames));
            for (EntityInfo entityWithLinkInfo : entitiesWithLinkInfos) {
                EntitiesGraph entitiesGraph = entitiesGraphsMap.get((UUID) entityWithLinkInfo.getId());
                //EntityGraph may be not created by this moment. It may happen if main entity contains none of the
                //search terms (all of them may be in related entities).
                if (entitiesGraph == null) {
                    entitiesGraph = new EntitiesGraph(entityWithLinkInfo);
                    entitiesGraphsMap.put((UUID) entityWithLinkInfo.getId(), entitiesGraph);
                }
                entitiesGraph.getLinkedEntitiesInfos().add(linkedEntitiesInfo);
            }
        }

        //the last step is to find entity graphs that contain all the search terms in any of their entities. Search terms
        //may be spread out among entities in graph, for example 'searchTerm1' may be in the main entity, but 'searchTerm2'
        //is in one of related entities.
        List<String> terms = Arrays.asList(searchTerm.split("\\s+"))
                .stream()
                .map(term -> term.replace("*", ".*") + ".*")
                .collect(Collectors.toList());

        IndexSearcher indexSearcher = null;
        try {
            indexSearcher = indexSearcherProvider.acquireIndexSearcher();
            for (Map.Entry<UUID, EntitiesGraph> entry : entitiesGraphsMap.entrySet()) {
                EntitiesGraph entitiesGraph = entry.getValue();
                Set<String> termsFoundedInGraph = findSearchTermsInEntitiesGraph(terms, entitiesGraph, indexSearcher);
                if (termsFoundedInGraph.containsAll(terms)) {
                    EntityInfo mainEntityInfo = entitiesGraph.getMainEntityInfo();
                    SearchResultEntry searchResultEntry = new SearchResultEntry(mainEntityInfo);
                    searchResultEntry.setDirectResult(false);
                    searchResult.addEntry(searchResultEntry);

                    //no hints, saying what fields contain search terms, are displayed at the moment. It requires some
                    //additional investigation
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error on finding FTS search terms in entities graph", e);
        } finally {
            if (indexSearcher != null)
                indexSearcherProvider.releaseIndexSearcher(indexSearcher);
        }

        return searchResult;
    }

    private Set<String> findSearchTermsInEntitiesGraph(Collection<String> terms, EntitiesGraph entitiesGraph,
                                                       IndexSearcher indexSearcher) throws IOException {
        Set<String> foundedSearchTerms = new HashSet<>();
        for (EntityInfo entityInfo : entitiesGraph.getAllEntityInfos()) {
            Collection<String> searchTermsForEntity = findSearchTermsByEntityInfo(terms, entityInfo, indexSearcher);
            foundedSearchTerms.addAll(searchTermsForEntity);
        }
        return foundedSearchTerms;
    }

    /**
     * Lucene index contains the indexed text. The text there is stored in the following format:
     * <pre>
     * ^^fieldName fieldValue ^^fieldName fieldValue ^^fieldName fieldValue
     * </pre>
     * The method queries lucene index for the text, parses the text and returns only those of the passed {@code terms}
     * that have a fieldValue that matches them.
     *
     * @param terms         a collection of search terms
     * @param entityInfo    an EntityInfo object that contains an information about the indexed entity
     * @param indexSearcher lucene index searcher
     * @return a collection of search terms that have a corresponding fieldValue in indexed text
     */
    private Collection<String> findSearchTermsByEntityInfo(Collection<String> terms, EntityInfo entityInfo,
                                                           IndexSearcher indexSearcher) throws IOException {
        Set<String> foundedTerms = new HashSet<>();
        String fldAll = loadIndexedText(entityInfo, indexSearcher);
        if (Strings.isNullOrEmpty(fldAll)) return Collections.emptySet();
        Matcher matcher = FIELD_VALUE_PATTERN.matcher(fldAll);
        while (matcher.find()) {
            String indexedPropertyValue = matcher.group(2);
            for (String term : terms) {
                if (isPropertyValueMatchesTerm(indexedPropertyValue, term)) {
                    foundedTerms.add(term);
                }
            }
        }
        return foundedTerms;
    }

    /**
     * Method creates a lucene query that finds documents by entity name and id
     */
    protected Query createQueryForEntityInfoSearch(EntityInfo entityInfo) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        TermQuery idQuery = new TermQuery(new Term(FLD_ID, entityInfo.getId().toString()));
        TermQuery entityNameQuery = new TermQuery(new Term(FLD_ENTITY, entityInfo.getEntityName()));
        builder.add(idQuery, BooleanClause.Occur.MUST);
        builder.add(entityNameQuery, BooleanClause.Occur.MUST);
        return builder.build();
    }

    /**
     * Methods queries lucene index for the text that have been indexed for the given entity. The text is stored in the
     * FLD_ALL field of the lucene document.
     *
     * @param entityInfo    an information about the entity
     * @param indexSearcher lucene index searcher
     * @return a text that have been indexed
     */
    @Nullable
    protected String loadIndexedText(EntityInfo entityInfo, IndexSearcher indexSearcher) throws IOException {
        String fldAllText = null;
        Query query = createQueryForEntityInfoSearch(entityInfo);
        TopDocs topDocs = indexSearcher.search(query, 1);
        if (topDocs.scoreDocs.length == 0) {
            log.warn("No result found for {}", entityInfo);
            return null;
        }
        Document doc = indexSearcher.doc(topDocs.scoreDocs[0].doc);
        fldAllText = doc.getField(FLD_ALL).stringValue();
        return fldAllText;
    }

    /**
     * Method returns true if any of the words in propertyValue matches with the term
     */
    private boolean isPropertyValueMatchesTerm(String propertyValue, String term) {
        for (String propertyValueWord : propertyValue.split("\\s+")) {
            if (propertyValueWord.toLowerCase().matches(term))
                return true;
        }
        return false;
    }

    /**
     * Class is used to store an information about what entity and related entities contain the search terms. For
     * example, if we searched for some term among 'Order' entities and the term was found in some order, then {@code
     * mainEntityInfo} field will contain an {@link EntityInfo} object for the Order class. If the search terms are also
     * found in one or many related entities then all these related entities infos will be placed to the {@code
     * linkedEntitiesInfos} collection.
     */
    private class EntitiesGraph {

        private EntityInfo mainEntityInfo;
        private List<EntityInfo> linkedEntitiesInfos = new ArrayList<>();

        public EntitiesGraph(EntityInfo mainEntityInfo) {
            this.mainEntityInfo = mainEntityInfo;
        }

        public EntityInfo getMainEntityInfo() {
            return mainEntityInfo;
        }

        public void setMainEntityInfo(EntityInfo mainEntityInfo) {
            this.mainEntityInfo = mainEntityInfo;
        }

        public List<EntityInfo> getLinkedEntitiesInfos() {
            return linkedEntitiesInfos;
        }

        public void setLinkedEntitiesInfos(List<EntityInfo> linkedEntitiesInfos) {
            this.linkedEntitiesInfos = linkedEntitiesInfos;
        }

        public Collection<EntityInfo> getAllEntityInfos() {
            Set<EntityInfo> allEntityInfos = new HashSet<>();
            allEntityInfos.add(mainEntityInfo);
            allEntityInfos.addAll(linkedEntitiesInfos);
            return allEntityInfos;
        }
    }
}