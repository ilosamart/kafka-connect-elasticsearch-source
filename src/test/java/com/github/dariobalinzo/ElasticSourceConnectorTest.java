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

package com.github.dariobalinzo;


import com.github.dariobalinzo.task.ElasticSourceTaskConfig;

import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ElasticSourceConnectorTest extends TestContainersContext {

    @Test
    public void shouldGetAListOfTasks() throws IOException {
        //given
        ElasticSourceConnector connector = new ElasticSourceConnector();
        connector.start(getConf());
        insertMockData(1, TEST_INDEX + 1);
        insertMockData(2, TEST_INDEX + 2);
        insertMockData(3, TEST_INDEX + 3);
        insertMockData(4, TEST_INDEX + 4);

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        //when
        int maxTasks = 3;
        List<Map<String, String>> taskList = connector.taskConfigs(maxTasks);

        //then
        assertEquals(maxTasks, taskList.size());
        assertNotNull(connector.version());
        connector.stop();
    }

    @Test
    public void shouldGetTaskFromFixedList() {
        //given
        ElasticSourceConnector connector = new ElasticSourceConnector();
        Map<String, String> conf = getConf();
        conf.remove(ElasticSourceTaskConfig.INDEX_PREFIX_CONFIG);
        conf.put(ElasticSourceTaskConfig.INDEX_NAMES_CONFIG, "index1,index2,index3");
        connector.start(conf);

        //when
        int maxTasks = 3;
        List<Map<String, String>> taskList = connector.taskConfigs(maxTasks);

        //then
        assertEquals(maxTasks, taskList.size());
        connector.stop();
    }

    @Test
    public void shouldUpdateListOfTopics() throws IOException {
        throw new UnsupportedOperationException();
        /*
        We need to mock the context but I don't know how to do it.

        I've tried with

        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>kafka-streams-test-utils</artifactId>
            <version>3.0.0</version>
            <scope>test</scope>
        </dependency>

        as described in https://docs.confluent.io/cloud/current/client-apps/testing.html
        with MockProcessorContext but it seems I'm wrong.
        */

        // //given
        // SourceConnectorContext context = new SourceConnectorContext();
        // ElasticSourceConnector connector = new ElasticSourceConnector();
        // connector.initialize(context);
        // connector.start(getConf());
        // insertMockData(1, TEST_INDEX + 1);

        // try {
        //     Thread.sleep(1000);
        // } catch (InterruptedException e) {
        //     // TODO Auto-generated catch block
        //     e.printStackTrace();
        // }

        // //when
        // int maxTasks = 1;
        // List<Map<String, String>> taskList = connector.taskConfigs(maxTasks);

        // //then
        // assertEquals(1, taskList.get(0).get(ElasticSourceConnectorConfig.INDICES_CONFIG).split(",").length);
        // assertNotNull(connector.version());

        // insertMockData(4, TEST_INDEX + 2);

        // try {
        //     Thread.sleep(connector.getPollMilisseconds()+1000);
        // } catch (InterruptedException e) {
        //     // TODO Auto-generated catch block
        //     e.printStackTrace();
        // }

        // taskList = connector.taskConfigs(maxTasks);

        // assertEquals(2, taskList.get(0).get(ElasticSourceConnectorConfig.INDICES_CONFIG).split(",").length);
        // assertNotNull(connector.version());

        // connector.stop();
    }

    private void UnsupportedOperationException() {
    }

}
