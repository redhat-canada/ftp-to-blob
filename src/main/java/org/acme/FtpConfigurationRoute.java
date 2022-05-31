package org.acme;

import io.quarkus.runtime.StartupEvent;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.apache.camel.component.kubernetes.KubernetesConstants;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.Map;

@ApplicationScoped
public class FtpConfigurationRoute extends EndpointRouteBuilder {

    @Inject
    ProducerTemplate producer;

    void onStart(@Observes StartupEvent ev) throws Exception {
        producer.sendBody("direct:main", "");
    }

    @Override
    public void configure() throws Exception {

        errorHandler(defaultErrorHandler().disableRedelivery().log("Error"));

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