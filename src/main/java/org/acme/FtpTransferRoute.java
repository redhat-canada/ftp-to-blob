package org.acme;

import io.fabric8.kubernetes.api.model.Secret;
import io.quarkus.runtime.StartupEvent;
import org.apache.camel.ConsumerTemplate;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.apache.camel.component.file.remote.RemoteFile;
import org.apache.camel.component.kubernetes.KubernetesConstants;
import org.apache.camel.support.processor.idempotent.MemoryIdempotentRepository;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@ApplicationScoped
public class FtpTransferRoute extends EndpointRouteBuilder {

    @Inject
    ConsumerTemplate consumer;

    @Inject
    ProducerTemplate producer;

    void onStart(@Observes StartupEvent ev) throws Exception {
        producer.sendBody("direct:main", "");
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

        from(direct("transfer")).routeId("transfer")
                .process(this::prepare)
                .filter(simple("${header.continue}"))
                    .toD("azure-storage-blob:kolobok/${header.container}?" +
                        "blobName=${header.filename}&" +
                        "operation=uploadBlockBlob&" +
                        "accessKey=RAW(${header.accessKey})");
    }

    private void prepare(Exchange exchange) {
        Secret secret = exchange.getIn().getBody(Secret.class);

        String hostname = getSecret(secret, "inHostname");
        String username = getSecret(secret, "inUsername");
        String password = getSecret(secret, "inPassword");
        String uri = ftp(username + "@" + hostname + "/files").passiveMode(true).noop(true)
                .password(password)
                .readLockMinAge("120s")
                .readLockCheckInterval(5000)
                .maxMessagesPerPoll(1)
                .sendEmptyMessageWhenIdle(true).getUri();
        log.info("Transferring from uri {}", uri);

        RemoteFile file = consumer.receiveBody(uri, RemoteFile.class);

        if (file != null) {
            String container = getSecret(secret, "outContainer");
            String accessKey = getSecret(secret, "outAccessKey");
            exchange.getIn().setHeader("accessKey", accessKey);
            exchange.getIn().setHeader("container", container);
            log.info("Transferring file {} to {}", file.getFileName(), container);
            exchange.getIn().setBody(file.getBody());
            exchange.getIn().setHeader("filename", file.getFileName());
            exchange.getIn().setHeader("continue", true);
        } else {
            log.info("No files to transfer from {}", uri);
            exchange.getIn().setHeader("continue", false);
        }
    }

    private String getSecret(Secret secret, String key) {
        return new String(Base64.getDecoder().decode(secret.getData().get(key).getBytes(StandardCharsets.UTF_8)));
    }
}
