package org.acme;

import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.apache.camel.component.kubernetes.KubernetesConstants;

import javax.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
public class HttpRunnerRoute extends EndpointRouteBuilder {

    @Override
    public void configure() throws Exception {

        from(platformHttp("/transfer/{pipe}")).routeId("rest")
                .setHeader(KubernetesConstants.KUBERNETES_SECRET_NAME, simple("${header.pipe}"))
                .setHeader(KubernetesConstants.KUBERNETES_NAMESPACE_NAME, constant("crashftp"))
                .to(kubernetesSecrets("kubernetes.default.svc").operation("getSecret"))
                .to(direct("transfer"));
    }
}
