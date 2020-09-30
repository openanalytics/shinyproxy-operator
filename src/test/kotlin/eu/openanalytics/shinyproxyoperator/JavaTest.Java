package eu.openanalytics.shinyproxyoperator;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.arquillian.cube.kubernetes.api.Session;
import org.arquillian.cube.kubernetes.impl.requirement.RequiresKubernetes;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(Arquillian.class)
@RequiresKubernetes
public class JavaTest {


    @ArquillianResource
    public static KubernetesClient client;

    @ArquillianResource
    public static Session session;

    @Test
    public void mainTest() {

        System.out.println(client.getVersion());

    }

}