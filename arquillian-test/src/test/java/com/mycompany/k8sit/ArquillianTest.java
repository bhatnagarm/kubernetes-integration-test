package com.mycompany.k8sit;

import com.jayway.jsonpath.JsonPath;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.v4_0.PodList;
import io.fabric8.kubernetes.api.model.v4_0.ServiceList;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import okhttp3.Response;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.arquillian.cube.kubernetes.annotations.Named;
import org.arquillian.cube.kubernetes.annotations.PortForward;
import org.arquillian.cube.kubernetes.api.Session;
import org.arquillian.cube.openshift.impl.client.OpenShiftAssistant;
import org.arquillian.cube.openshift.impl.enricher.RouteURL;
import org.arquillian.cube.openshift.impl.requirement.RequiresOpenshift;
import org.arquillian.cube.requirement.ArquillianConditionalRunner;
import org.awaitility.Awaitility;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.mockserver.client.server.MockServerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;

import javax.jms.TextMessage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

@Category(RequiresOpenshift.class)
@RunWith(ArquillianConditionalRunner.class)
@RequiresOpenshift
//@RunWith(Arquillian.class) //Can use this simply if don't want to force condition "with OpenShift only"
public class ArquillianTest {
    private static final Logger log = LoggerFactory.getLogger(ArquillianTest.class);

    @ArquillianResource
    io.fabric8.openshift.client.OpenShiftClient oc;
    @ArquillianResource
    io.fabric8.kubernetes.client.KubernetesClient client;

    //Causes: java.lang.IllegalArgumentException: Can not set io.fabric8.openshift.clnt.v4_0.OpenShiftClient field com.mycompany.k8sit.ArquillianTest.oc4 to io.fabric8.kubernetes.clnt.v4_0.DefaultKubernetesClient
//    @ArquillianResource
//    io.fabric8.kubernetes.clnt.v4_0.KubernetesClient client4;
//    @ArquillianResource
//    io.fabric8.openshift.clnt.v4_0.OpenShiftClient oc4;


    //Forward amq port so it's available on a localhost port.
    @Named("amqsvc")
    @PortForward
    @ArquillianResource
    URL amqsvcUrl;

    //We can of course do the same port-forward here, but let's use a Route instead as this service is http
    // @AwaitRoute //It requires a GET endpoint, but mock-server has none by default
    @RouteURL("mockserverroute")
    URL mockserver;

    @Named("mariadb")
    @ArquillianResource
    Pod mariadb;


    //Not used, these are here only for example:
    @ArquillianResource //Arquillian namespace: session.getNamespace() or oc.getNamespace()
    Session session;
    @ArquillianResource //Same as oc.services().list()
    ServiceList services;
    @ArquillianResource //Same as oc.pods().list()
    PodList pods;
    @ArquillianResource //Additional helper to deploy
    OpenShiftAssistant openShiftAssistant;


    @BeforeClass
    public static void beforeClass(){
        //clients are not available yet (even if static)
    }

    private boolean beforeDone = false;
    @Before
    public void before() throws Exception{
        if (!beforeDone){

            log.info("Before is running. oc client: {}",oc.getOpenshiftUrl());

            // Prepare database;
            // Run mysql client in container with oc cli tool
            // oc exec waits for the command to finish
            //Runtime rt = Runtime.getRuntime();
            //Process mysql = rt.exec("oc exec -i -n "+session.getNamespace()+" mariadb -- /opt/rh/rh-mariadb102/root/usr/bin/mysql -u myuser -pmypassword -h 127.0.0.1 testdb");
            //IOUtils.copy(this.getClass().getClassLoader().getResourceAsStream("sql/sql-load.sql"),mysql.getOutputStream() );
            //mysql.getOutputStream().close();
            //log.info("waitFor: {}",mysql.waitFor());
            //log.info("output: {}", IOUtils.toString(mysql.getInputStream()));
            //log.info("error: {}", IOUtils.toString(mysql.getErrorStream()));

            // Prepare database
            loadSql();


            //Prepare MockServer response for test
            log.info("mockserver URL: {}",mockserver);
            int port = mockserver.getPort() == -1 ? 80 : mockserver.getPort();
            log.info("mockserver {} {}",mockserver.getHost(),port);
            //We could use any http client to call the MockServer expectation api, but this java client is nicer
            MockServerClient mockServerClient = new MockServerClient(mockserver.getHost(),port);
            mockServerClient
                    .when(
                            request()
                                    .withMethod("GET")
                                    .withPath("/v1/address/email/testSucc@test.com")
                    )
                    .respond(
                            response()
                                    .withBody("{\n" +
                                            "\"state\": \"Test State\",\n" +
                                            "\"city\": \"Test City\",\n" +
                                            "\"address\": \"1 Test St\",\n" +
                                            "\"zip\": \"T001\"\n" +
                                            "}\n")
                    );

            beforeDone=true;
        }
    }

    //Happy path
    @Test
    public void testSucc() throws Exception {

        log.info("Test resources are in namespace:"+session.getNamespace());

        //OpenShift client - here for example
        log.info("OpenShift - getMasterUrl: {}",  oc.getMasterUrl());
        log.info("OpenShift - getApiVersion: {}",  oc.getApiVersion());
        log.info("OpenShift - currentUser: {}",  oc.currentUser().getMetadata().getName());
        log.info("OpenShift - getNamespace: {}",  oc.getNamespace());
        log.info("OpenShift - getConfiguration: {}",  oc.getConfiguration());
        log.info("OpenShift - getClass: {}",  oc.getClass());

        //Kubernetes client - here for example
        log.info("Kubernetes - getMasterUrl: {}",  client.getMasterUrl());
        log.info("Kubernetes - getApiVersion: {}",  client.getApiVersion());
        log.info("Kubernetes - getConfiguration: {}",  client.getConfiguration());
        log.info("Kubernetes - getClass: {}",  client.getClass());

        //Service in the current namespace
        oc.services().list().getItems().stream()
                .map(s->s.getMetadata().getNamespace()+" - "+s.getMetadata().getName())
                .forEach(s->log.info("Service: {}",s));

        /*************
         * Start test
         *************/
        //The port-foward url has http schema, but it's actually the amq 61616 port
        String brokerUrl = "tcp://"+ amqsvcUrl.getHost()+":"+ amqsvcUrl.getPort();
        log.info("brokerUrl: {}",brokerUrl);
        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory("test","secret",brokerUrl);
        JmsTemplate jmsTemplate = new JmsTemplate(connectionFactory);

        //Send test message and receive outcome
        jmsTemplate.convertAndSend("user.in", "{\"email\":\"testSucc@test.com\"}");
        TextMessage message = (TextMessage) jmsTemplate.receive("user.out");
        String response = message.getText();

        log.info("Response: {}",response);

        //Asserts
        assertEquals("testSucc@test.com", JsonPath.read(response, "$.email"));
        assertEquals("5551234567", JsonPath.read(response, "$.phone"));
        assertEquals("Test State", JsonPath.read(response, "$.address.state"));
        assertEquals("Test City", JsonPath.read(response, "$.address.city"));
        assertEquals("1 Test St", JsonPath.read(response, "$.address.address"));
        assertEquals("T001", JsonPath.read(response, "$.address.zip"));

    }

    //Test verifies that the retry works in case of temporary database downtime
    @Test
    public void shutDownMariadb() throws Exception{
        //Delete mariadb pod
        log.info("mariadb: {}", oc.pods().withName("mariadb").get()); //not null
        log.info("Delete mariadb pod.");
        assertTrue( oc.pods().withName(mariadb.getMetadata().getName()).delete() );
        Awaitility.await().atMost(30,TimeUnit.SECONDS).until(()->oc.pods().withName("mariadb").get()==null);

        //The port-foward url has http schema, but it's actually the amq 61616 port
        String brokerUrl = "tcp://"+ amqsvcUrl.getHost()+":"+ amqsvcUrl.getPort();
        log.info("brokerUrl: {}",brokerUrl);
        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory("test","secret",brokerUrl);
        JmsTemplate jmsTemplate = new JmsTemplate(connectionFactory);

        //Send test message and receive outcome
        jmsTemplate.convertAndSend("user.in", "{\"email\":\"testSucc@test.com\"}");

        //Wait a sec to make sure message fails
        Thread.sleep(3000);

        //Create mariadb Pod and load sql
        log.info("Create mariadb pod.");
        ObjectMeta emptyObjectMeta = new ObjectMeta();
        emptyObjectMeta.setName(mariadb.getMetadata().getName());
        emptyObjectMeta.setLabels(mariadb.getMetadata().getLabels());
        mariadb.setMetadata(emptyObjectMeta);
        mariadb.setStatus(null);
        Pod newPod = oc.pods().create(mariadb);
        log.info("mariadb: {}", oc.pods().withName("mariadb").get());
        Awaitility.await().atMost(30,TimeUnit.SECONDS).until(()->oc.pods().withName("mariadb").isReady());
        //Recreate table, load data
        loadSql();

        //Receive response message
        TextMessage message = (TextMessage) jmsTemplate.receive("user.out");
        String response = message.getText();

        log.info("Response: {}",response);

        //Asserts
        assertEquals("testSucc@test.com", JsonPath.read(response, "$.email"));
        assertEquals("5551234567", JsonPath.read(response, "$.phone"));
        assertEquals("Test State", JsonPath.read(response, "$.address.state"));
        assertEquals("Test City", JsonPath.read(response, "$.address.city"));
        assertEquals("1 Test St", JsonPath.read(response, "$.address.address"));
        assertEquals("T001", JsonPath.read(response, "$.address.zip"));

    }

    private void loadSql() throws Exception{
        // Run command in container using OpenShiftClient java client - run sql in mysql
        log.info("Sql load - start");
        final CountDownLatch latch = new CountDownLatch(1);
        OutputStream execOut = new ByteArrayOutputStream();
        OutputStream execErr = new ByteArrayOutputStream();
        ExecWatch exec = oc.pods().withName("mariadb")
                .readingInput(this.getClass().getClassLoader().getResourceAsStream("sql/sql-load.sql"))
                .writingOutput(execOut)
                .writingError(execErr)
                //.withTTY() //Optional
                .usingListener(createCountDownListener(latch))
                .exec("/opt/rh/rh-mariadb102/root/usr/bin/mysql","-u", "myuser", "-pmypassword", "-h", "127.0.0.1", "testdb")
                ;
        if (!latch.await(20, TimeUnit.SECONDS)) {
            throw new Exception("Exec timeout");
        }
        log.info("Exec out: {}", ((ByteArrayOutputStream) execOut).toString());
        log.info("Exec err: {}", ((ByteArrayOutputStream) execErr).toString());
        log.info("Sql load - end");
    }

    //Helper method for OpenShiftClient exec()
    public static ExecListener createCountDownListener(CountDownLatch latch){
        return new ExecListener() {
            @Override
            public void onOpen(Response response) {
                log.info("onOpen response: {}",response);
            }

            @Override
            public void onFailure(Throwable t, Response response) {
                log.info("onFailure response: {}",response,t);
                latch.countDown();

            }

            @Override
            public void onClose(int code, String reason) {
                log.info("onClose reason: {} {}",code, reason);
                latch.countDown();

            }
        };
    }

}
