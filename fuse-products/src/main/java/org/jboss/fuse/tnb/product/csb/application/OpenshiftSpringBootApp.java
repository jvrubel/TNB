package org.jboss.fuse.tnb.product.csb.application;

import org.jboss.fuse.tnb.common.config.TestConfiguration;
import org.jboss.fuse.tnb.common.openshift.OpenshiftClient;
import org.jboss.fuse.tnb.common.utils.WaitUtils;
import org.jboss.fuse.tnb.product.integration.IntegrationBuilder;
import org.jboss.fuse.tnb.product.log.OpenshiftLog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import io.fabric8.kubernetes.client.utils.PodStatusUtil;

public class OpenshiftSpringBootApp extends SpringBootApp {

    private static final Logger LOG = LoggerFactory.getLogger(OpenshiftSpringBootApp.class);

    public OpenshiftSpringBootApp(IntegrationBuilder integrationBuilder) {
        super(integrationBuilder);
    }

    @Override
    public void start() {
        log = new OpenshiftLog(p -> p.getMetadata().getLabels().containsKey("app")
            && name.equals(p.getMetadata().getLabels().get("app"))
            && p.getMetadata().getLabels().containsKey("provider")
            && "jkube".equals(p.getMetadata().getLabels().get("provider")));
    }

    @Override
    public void stop() {
        LOG.info("Collecting logs of integration {}", name);
        org.jboss.fuse.tnb.common.utils.IOUtils.writeFile(TestConfiguration.appLocation().resolve(name + ".log"), getLog().toString());
        LOG.info("Undeploying integration resources");
        final ObjectMeta labels = new ObjectMeta();
        final Map<String, String> labelMap = Map.of("app", name, "provider", "jkube");
        labels.setLabels(labelMap);
        //builds
        OpenshiftClient.get().buildConfigs().withLabels(labelMap).delete();
        OpenshiftClient.get().builds().withLabels(labelMap).delete();
        OpenshiftClient.get().imageStreams().withLabels(labelMap).delete();
        //app
        OpenshiftClient.get().deploymentConfigs().withLabels(labelMap).delete();
        //network
        OpenshiftClient.get().services().withLabels(labelMap).delete();
        OpenshiftClient.get().routes().withLabels(labelMap).delete();
        //remaning pods
        OpenshiftClient.get().pods().withLabels(labelMap).delete();
    }

    @Override
    public boolean isReady() {
        WaitUtils.waitFor(() -> OpenshiftClient.get().getLabeledPods(Map.of("app", name, "provider", "jkube"))
                .stream().allMatch(pod -> Readiness.isPodReady(pod))
            , 6, 10 * 1000L, "Waiting for application deployed"
        );
        return true;
    }

    @Override
    public boolean isFailed() {
        return OpenshiftClient.get().getLabeledPods(Map.of("app", name, "provider", "jkube"))
            .stream().map(PodStatusUtil::getContainerStatus)
            .allMatch(containerStatuses -> containerStatuses.stream()
                .noneMatch(containerStatus -> "error".equalsIgnoreCase(containerStatus.getLastState().getTerminated().getReason())));
    }
}
