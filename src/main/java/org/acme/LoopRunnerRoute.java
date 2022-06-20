package org.acme;

import io.quarkus.runtime.StartupEvent;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.apache.camel.component.kubernetes.KubernetesConstants;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.Map;

@ApplicationScoped
public class LoopRunnerRoute extends EndpointRouteBuilder {

    @ConfigProperty(name = "transfer.loop")
    boolean loop;

    @Inject
    ProducerTemplate producer;

    void onStart(@Observes StartupEvent ev) throws Exception {
        if (loop) producer.sendBody("direct:main", "");
    }

    @Override
    public void configure() throws Exception {

        from(direct("main")).routeId("main")
                .loopDoWhile(constant(true))
                    .to(direct("pipes"))
                    .to(direct("transfer-loop"))
                .end();

        from(direct("transfer-loop")).routeId("transfer-loop")
                .split(body())
                    .to(direct("transfer"))
                .end();

        from(direct("pipes")).routeId("pipes")
                .setHeader(KubernetesConstants.KUBERNETES_SECRETS_LABELS, constant(Map.of("app", "crashftp")))
                .to(kubernetesSecrets("kubernetes.default.svc").operation("listSecretsByLabels"));
    }
}
