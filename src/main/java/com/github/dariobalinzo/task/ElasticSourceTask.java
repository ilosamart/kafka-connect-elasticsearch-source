/*
 * Copyright © 2018 Dario Balinzo (dariobalinzo@gmail.com)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.dariobalinzo.task;

import com.github.dariobalinzo.ElasticSourceConnector;
import com.github.dariobalinzo.ElasticSourceConnectorConfig;
import com.github.dariobalinzo.Version;
import com.github.dariobalinzo.elastic.ElasticConnection;
import com.github.dariobalinzo.elastic.ElasticRepository;
import com.github.dariobalinzo.elastic.PageResult;
import com.github.dariobalinzo.filter.DocumentFilter;
import com.github.dariobalinzo.filter.WhitelistFilter;
import com.github.dariobalinzo.schema.SchemaConverter;
import com.github.dariobalinzo.schema.StructConverter;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ElasticSourceTask extends SourceTask {

    private static final Logger logger = LoggerFactory.getLogger(ElasticSourceTask.class);
    private static final String INDEX = "index";
    static final String POSITION = "position";

    private final SchemaConverter schemaConverter = new SchemaConverter();
    private final StructConverter structConverter = new StructConverter();

    private ElasticSourceTaskConfig config;
    private ElasticConnection es;

    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private List<String> indices;
    private String topic;
    private String cursorField;
    private int pollingMs;
    private final Map<String, String> last = new HashMap<>();
    private final Map<String, Integer> sent = new HashMap<>();
    private ElasticRepository elasticRepository;

    private final List<DocumentFilter> documentFilters = new ArrayList<>();

    public ElasticSourceTask() {

    }

    @Override
    public String version() {
        return Version.VERSION;
    }

    @Override
    public void start(Map<String, String> properties) {
        try {
            config = new ElasticSourceTaskConfig(properties);
        } catch (ConfigException e) {
            throw new ConnectException("Couldn't start ElasticSourceTask due to configuration error", e);
        }

        indices = Arrays.asList(config.getString(ElasticSourceTaskConfig.INDICES_CONFIG).split(","));
        if (indices.isEmpty()) {
            throw new ConnectException("Invalid configuration: each ElasticSourceTask must have at "
                    + "least one index assigned to it");
        }

        topic = config.getString(ElasticSourceConnectorConfig.TOPIC_PREFIX_CONFIG);
        cursorField = config.getString(ElasticSourceConnectorConfig.INCREMENTING_FIELD_NAME_CONFIG);
        pollingMs = Integer.parseInt(config.getString(ElasticSourceConnectorConfig.POLL_INTERVAL_MS_CONFIG));

        initConnectorFilter();


        initEsConnection();
    }

    private void initConnectorFilter() {
        String whiteFilters = config.getString(ElasticSourceConnectorConfig.FIELDS_WHITELIST_CONFIG);

        if (whiteFilters != null) {
            String[] whiteFiltersArray = whiteFilters.split(";");
            Set<String> whiteFiltersSet = new HashSet<>(Arrays.asList(whiteFiltersArray));
            documentFilters.add(new WhitelistFilter(whiteFiltersSet));
        }
    }

    private void initEsConnection() {
        String esScheme = config.getString(ElasticSourceConnectorConfig.ES_SCHEME_CONF);
        String esHost = config.getString(ElasticSourceConnectorConfig.ES_HOST_CONF);
        int esPort = Integer.parseInt(config.getString(ElasticSourceConnectorConfig.ES_PORT_CONF));

        String esUser = config.getString(ElasticSourceConnectorConfig.ES_USER_CONF);
        String esPwd = config.getString(ElasticSourceConnectorConfig.ES_PWD_CONF);

        int batchSize = Integer.parseInt(config.getString(ElasticSourceConnectorConfig.BATCH_MAX_ROWS_CONFIG));

        int maxConnectionAttempts = Integer.parseInt(config.getString(
                ElasticSourceConnectorConfig.CONNECTION_ATTEMPTS_CONFIG
        ));
        long connectionRetryBackoff = Long.parseLong(config.getString(
                ElasticSourceConnectorConfig.CONNECTION_BACKOFF_CONFIG
        ));
        if (esUser == null || esUser.isEmpty()) {
            es = new ElasticConnection(
                    esHost,
                    esScheme,
                    esPort,
                    maxConnectionAttempts,
                    connectionRetryBackoff
            );
        } else {
            es = new ElasticConnection(
                    esHost,
                    esScheme,
                    esPort,
                    esUser,
                    esPwd,
                    maxConnectionAttempts,
                    connectionRetryBackoff
            );

        }

        elasticRepository = new ElasticRepository(es, cursorField);
        elasticRepository.setPageSize(batchSize);
    }


    //will be called by connect with a different thread than the stop thread
    @Override
    public List<SourceRecord> poll() {
        List<SourceRecord> results = new ArrayList<>();
        try {
            for (String index : indices) {
                if (!stopping.get()) {
                    logger.info("fetching from {}", index);
                    String lastValue = fetchLastOffset(index);
                    logger.info("found last value {}", lastValue);
                    PageResult pageResult = elasticRepository.searchAfter(index, lastValue);
                    parseResult(pageResult, results);
                    logger.info("index {} total messages: {} ", index, sent.get(index));
                }
            }
            if (results.isEmpty()) {
                logger.info("no data found, sleeping for {} ms", pollingMs);
                Thread.sleep(pollingMs);
            }

        } catch (Exception e) {
            logger.error("error", e);
        }
        return results;
    }

    private String fetchLastOffset(String index) {
        //first we check in cache memory the last value
        if (last.get(index) != null) {
            return last.get(index);
        }

        //if cache is empty we check the framework
        Map<String, Object> offset = context.offsetStorageReader().offset(Collections.singletonMap(INDEX, index));
        if (offset != null) {
            return (String) offset.get(POSITION);
        } else {
            return null;
        }
    }

    private void parseResult(PageResult pageResult, List<SourceRecord> results) {
        String index = pageResult.getIndex();
        for (Map<String, Object> originalDocument : pageResult.getDocuments()) {
            Map<String, String> sourcePartition = Collections.singletonMap(INDEX, index);
            Map<String, String> sourceOffset = Collections.singletonMap(POSITION, originalDocument.get(cursorField).toString());

            Map<String, Object> filteredDocument = originalDocument;
            for (DocumentFilter documentFilter : documentFilters) {
                filteredDocument = documentFilter.filter(filteredDocument);
            }

            Schema schema = schemaConverter.convert(filteredDocument, index);
            Struct struct = structConverter.convert(filteredDocument, schema);

            String key = String.join("_", index, originalDocument.get(cursorField).toString());
            SourceRecord sourceRecord = new SourceRecord(
                    sourcePartition,
                    sourceOffset,
                    topic + index,
                    //KEY
                    Schema.STRING_SCHEMA,
                    key,
                    //VALUE
                    schema,
                    struct);
            results.add(sourceRecord);

            last.put(index, originalDocument.get(cursorField).toString());
            sent.merge(index, 1, Integer::sum);
        }
    }

    //will be called by connect with a different thread than poll thread
    public void stop() {
        stopping.set(true);
        if (es != null) {
            es.closeQuietly();
        }
    }
}