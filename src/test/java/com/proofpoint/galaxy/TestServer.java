/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.proofpoint.galaxy;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import com.proofpoint.configuration.ConfigurationFactory;
import com.proofpoint.configuration.ConfigurationModule;
import com.proofpoint.experimental.json.JsonCodec;
import com.proofpoint.experimental.json.JsonCodecBuilder;
import com.proofpoint.http.server.testing.TestingHttpServer;
import com.proofpoint.http.server.testing.TestingHttpServerModule;
import com.proofpoint.jaxrs.JaxrsModule;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.io.File;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static javax.ws.rs.core.Response.Status;
import static com.proofpoint.galaxy.ExtraAssertions.assertEqualsNoOrder;
import static com.proofpoint.galaxy.LifecycleState.RUNNING;
import static com.proofpoint.galaxy.LifecycleState.STOPPED;
import static com.proofpoint.galaxy.LifecycleState.UNASSIGNED;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class TestServer
{
    private static final int NOT_ALLOWED = 405;

    private AsyncHttpClient client;
    private TestingHttpServer server;

    private Agent agent;

    private final JsonCodec<AssignmentRepresentation> assignmentCodec = new JsonCodecBuilder().build(AssignmentRepresentation.class);
    private final JsonCodec<Map<String, Object>> mapCodec = new JsonCodecBuilder().build(new TypeLiteral<Map<String, Object>>() {});
    private final JsonCodec<List<Map<String, Object>>> listCodec = new JsonCodecBuilder().build(new TypeLiteral<List<Map<String, Object>>>() {});

    private Assignment appleAssignment;
    private Assignment bananaAssignment;
    private File tempDir;
    private File testRepository;


    @BeforeClass
    public void startServer()
            throws Exception
    {
        tempDir = DeploymentUtils.createTempDir("agent");
        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("agent.console-uri", "http://localhost:9999/")
                .put("agent.slots-dir", tempDir.getAbsolutePath())
                .build();

        Injector injector = Guice.createInjector(new TestingHttpServerModule(),
                new JaxrsModule(),
                new AgentMainModule(),
                new ConfigurationModule(new ConfigurationFactory(properties)));

        server = injector.getInstance(TestingHttpServer.class);
        agent = injector.getInstance(Agent.class);

        server.start();
        client = new AsyncHttpClient();

        testRepository = RepositoryTestHelper.createTestRepository();
        appleAssignment = newAssignment("apple", "1.0");
        bananaAssignment = newAssignment("banana", "2.0-SNAPSHOT");
    }

    private Assignment newAssignment(String name, String binaryVersion)
    {
        BinarySpec binarySpec = BinarySpec.valueOf("food.fruit:" + name + ":" + binaryVersion);
        ConfigSpec configSpec = ConfigSpec.valueOf("@prod:" + name + ":1.0");
        return new Assignment(binarySpec, DeploymentUtils.toMavenRepositoryPath(testRepository.toURI(), binarySpec), configSpec, ImmutableMap.<String, URI>of());
    }

    @BeforeMethod
    public void resetState()
    {
        for (Slot slot : agent.getAllSlots()) {
            agent.deleteSlot(slot.getName());
        }
        assertTrue(agent.getAllSlots().isEmpty());
    }

    @AfterClass
    public void stopServer()
            throws Exception
    {
        if (server != null) {
            server.stop();
        }

        if (client != null) {
            client.close();
        }
        if (tempDir != null) {
            DeploymentUtils.deleteRecursively(tempDir);
        }
        if (testRepository != null) {
            DeploymentUtils.deleteRecursively(testRepository);
        }
    }

    @Test
    public void testGetSlotStatus()
            throws Exception
    {
        Slot slot = agent.addNewSlot();
        slot.assign(appleAssignment);

        Response response = client.prepareGet(urlFor("/v1/slot/" + slot.getName())).execute().get();

        assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        assertEquals(response.getContentType(), MediaType.APPLICATION_JSON);

        Map<String, Object> expected = mapCodec.fromJson(Resources.toString(Resources.getResource("slot-status.json"), Charsets.UTF_8));
        expected.put("id", slot.getId().toString());
        expected.put("name", slot.getName());
        expected.put("self", urlFor(slot));

        Map<String, Object> actual = mapCodec.fromJson(response.getResponseBody());
        assertEquals(actual, expected);
    }

    @Test
    public void testGetAllSlotStatusEmpty()
            throws Exception
    {
        Response response = client.prepareGet(urlFor("/v1/slot")).execute().get();

        assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        assertEquals(response.getContentType(), MediaType.APPLICATION_JSON);
        assertEquals(listCodec.fromJson(response.getResponseBody()), Collections.<Object>emptyList());
    }

    @Test
    public void testGetAllSlotStatus()
            throws Exception
    {
        Slot slot0 = agent.addNewSlot();
        slot0.assign(appleAssignment);
        Slot slot1 = agent.addNewSlot();
        slot1.assign(bananaAssignment);

        Response response = client.prepareGet(urlFor("/v1/slot")).execute().get();

        assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        assertEquals(response.getContentType(), MediaType.APPLICATION_JSON);

        List<Map<String, Object>> expected = listCodec.fromJson(Resources.toString(Resources.getResource("slot-status-list.json"), Charsets.UTF_8));
        expected.get(0).put("id", slot0.getId().toString());
        expected.get(0).put("name", slot0.getName());
        expected.get(0).put("self", urlFor(slot0));
        expected.get(1).put("id", slot1.getId().toString());
        expected.get(1).put("name", slot1.getName());
        expected.get(1).put("self", urlFor(slot1));

        List<Map<String, Object>> actual = listCodec.fromJson(response.getResponseBody());
        assertEqualsNoOrder(actual, expected);
    }

    @Test
    public void testAddSlot()
            throws Exception
    {
        Response response = client.preparePost(urlFor("/v1/slot")).execute().get();
        assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode());

        // find the new slot
        Slot slot = agent.getAllSlots().iterator().next();

        assertEquals(response.getHeader(HttpHeaders.LOCATION), server.getBaseUrl().resolve("/v1/slot/").resolve(slot.getName()).toString());
    }


    @Test
    public void testRemoveSlot()
            throws Exception
    {
        Slot slot = agent.addNewSlot();
        slot.assign(appleAssignment);

        Response response = client.prepareDelete(urlFor("/v1/slot/" + slot.getName())).execute().get();

        assertEquals(response.getStatusCode(), Status.NO_CONTENT.getStatusCode());

        assertNull(agent.getSlot(slot.getName()));
    }

    @Test
    public void testRemoveSlotUnknown()
            throws Exception
    {
        Response response = client.prepareDelete(urlFor("/v1/slot/unknown"))
                .execute()
                .get();

        assertEquals(response.getStatusCode(), Status.NOT_FOUND.getStatusCode());
    }

    @Test
    public void testPutNotAllowed()
            throws Exception
    {
        String json = Resources.toString(Resources.getResource("slot-status.json"), Charsets.UTF_8);
        Response response = client.preparePut(urlFor("/v1/slot"))
                .setBody(json)
                .execute()
                .get();

        assertEquals(response.getStatusCode(), NOT_ALLOWED);

        assertNull(agent.getSlot("slot1"));
    }

    @Test
    public void testAssign()
            throws Exception
    {
        Slot slot = agent.addNewSlot();

        String json = assignmentCodec.toJson(AssignmentRepresentation.from(appleAssignment));
        Response response = client.preparePut(urlFor(slot) + "/assignment")
                .setBody(json)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .execute()
                .get();

        assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        assertEquals(response.getContentType(), MediaType.APPLICATION_JSON);

        Map<String, String> expected = ImmutableMap.<String, String>builder()
                .put("id", slot.getId().toString())
                .put("name", slot.getName())
                .put("binary", appleAssignment.getBinary().toString())
                .put("config", appleAssignment.getConfig().toString())
                .put("self", urlFor(slot))
                .put("status", STOPPED.toString())
                .build();

        Map<String, Object> actual = mapCodec.fromJson(response.getResponseBody());
        assertEquals(actual, expected);
    }

    @Test
    public void testClear()
            throws Exception
    {
        Slot slot = agent.addNewSlot();

        Response response = client.prepareDelete(urlFor(slot) + "/assignment").execute().get();

        assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        assertEquals(response.getContentType(), MediaType.APPLICATION_JSON);

        Map<String, String> expected = ImmutableMap.<String, String>builder()
                .put("id", slot.getId().toString())
                .put("name", slot.getName())
                .put("self", urlFor(slot))
                .put("status", UNASSIGNED.toString())
                .build();

        Map<String, Object> actual = mapCodec.fromJson(response.getResponseBody());
        assertEquals(actual, expected);
    }

    @Test
    public void testStart()
            throws Exception
    {
        Slot slot = agent.addNewSlot();
        slot.assign(appleAssignment);

        Response response = client.preparePut(urlFor(slot) + "/lifecycle")
                .setBody("start")
                .execute()
                .get();

        assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        assertEquals(response.getContentType(), MediaType.APPLICATION_JSON);

        Map<String, String> expected = ImmutableMap.<String, String>builder()
                .put("id", slot.getId().toString())
                .put("name", slot.getName())
                .put("binary", appleAssignment.getBinary().toString())
                .put("config", appleAssignment.getConfig().toString())
                .put("self", urlFor(slot))
                .put("status", RUNNING.toString())
                .build();

        Map<String, Object> actual = mapCodec.fromJson(response.getResponseBody());
        assertEquals(actual, expected);
    }

    @Test
    public void testStop()
            throws Exception
    {
        Slot slot = agent.addNewSlot();
        slot.assign(appleAssignment);
        slot.start();

        Response response = client.preparePut(urlFor(slot) + "/lifecycle")
                .setBody("stop")
                .execute()
                .get();

        assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        assertEquals(response.getContentType(), MediaType.APPLICATION_JSON);

        Map<String, String> expected = ImmutableMap.<String, String>builder()
                .put("id", slot.getId().toString())
                .put("name", slot.getName())
                .put("binary", appleAssignment.getBinary().toString())
                .put("config", appleAssignment.getConfig().toString())
                .put("self", urlFor(slot))
                .put("status", STOPPED.toString())
                .build();

        Map<String, Object> actual = mapCodec.fromJson(response.getResponseBody());
        assertEquals(actual, expected);
    }

    @Test
    public void testRestart()
            throws Exception
    {
        Slot slot = agent.addNewSlot();
        slot.assign(appleAssignment);

        Response response = client.preparePut(urlFor(slot) + "/lifecycle")
                .setBody("restart")
                .execute()
                .get();

        assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        assertEquals(response.getContentType(), MediaType.APPLICATION_JSON);

        Map<String, String> expected = ImmutableMap.<String, String>builder()
                .put("id", slot.getId().toString())
                .put("name", slot.getName())
                .put("binary", appleAssignment.getBinary().toString())
                .put("config", appleAssignment.getConfig().toString())
                .put("self", urlFor(slot))
                .put("status", RUNNING.toString())
                .build();

        Map<String, Object> actual = mapCodec.fromJson(response.getResponseBody());
        assertEquals(actual, expected);
    }

    @Test
    public void testLifecycleUnknown()
            throws Exception
    {
        Slot slot = agent.addNewSlot();
        slot.assign(appleAssignment);

        Response response = client.preparePut(urlFor(slot) + "/lifecycle")
                .setBody("unknown")
                .execute()
                .get();

        assertEquals(response.getStatusCode(), Status.BAD_REQUEST.getStatusCode());
    }

    private String urlFor(String path)
    {
        return server.getBaseUrl().resolve(path).toString();
    }

    private String urlFor(Slot slot)
    {
        return server.getBaseUrl().resolve("/v1/slot/").resolve(slot.getName()).toString();
    }
}
