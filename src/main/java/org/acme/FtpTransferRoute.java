package org.acme;

import io.fabric8.kubernetes.api.model.Secret;
import org.apache.camel.ConsumerTemplate;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.apache.camel.component.file.remote.RemoteFile;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@ApplicationScoped
public class FtpTransferRoute extends EndpointRouteBuilder {

    @Inject
    ConsumerTemplate consumer;

    @Override
    public void configure() throws Exception {

        errorHandler(defaultErrorHandler().disableRedelivery().log("Error during transferring"));

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
                .readLock("changed")
                .readLockMinAge("120s")
                .readLockCheckInterval(5000)
                .exclude("exclude=inprogress-.*")
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
