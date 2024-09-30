package software.tnb.product.cq.application;

import software.tnb.common.config.OpenshiftConfiguration;
import software.tnb.common.config.TestConfiguration;
import software.tnb.common.utils.WaitUtils;
import software.tnb.product.application.App;
import software.tnb.product.application.Phase;
import software.tnb.product.cq.configuration.QuarkusConfiguration;
import software.tnb.product.integration.builder.AbstractIntegrationBuilder;
import software.tnb.product.integration.builder.CamelJBangIntegrationBuilder;
import software.tnb.product.integration.generator.IntegrationGenerator;
import software.tnb.product.log.stream.LogStream;
import software.tnb.product.util.maven.BuildRequest;
import software.tnb.product.util.maven.Maven;

import org.apache.commons.io.FileUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

public abstract class QuarkusApp extends App {
    private static final Logger LOG = LoggerFactory.getLogger(QuarkusApp.class);

    protected BooleanSupplier readinessCheck;

    public QuarkusApp(AbstractIntegrationBuilder<?> integrationBuilder) {
        super(integrationBuilder);

        this.integrationBuilder = integrationBuilder;

        if (integrationBuilder instanceof CamelJBangIntegrationBuilder) {
            createUsingJBang();
        } else {
            createWithMaven();
        }

        customizeProject(integrationBuilder.getDependencies());
        customizePlugins(integrationBuilder.getPlugins());

        Map<String, String> properties = new HashMap<>(Map.of(
            "skipTests", "true",
            "quarkus.native.container-build", "true"
        ));
        properties.putAll(QuarkusConfiguration.fromSystemProperties());

        BuildRequest.Builder requestBuilder = new BuildRequest.Builder()
            .withBaseDirectory(TestConfiguration.appLocation().resolve(name))
            .withGoals("clean", "package")
            .withProperties(properties)
            .withLogFile(getLogPath(Phase.BUILD))
            .withLogMarker(LogStream.marker(name, Phase.BUILD));
        if (QuarkusConfiguration.isQuarkusNative() && !OpenshiftConfiguration.isOpenshift()) {
            // Native build is performed in the OCP deploy, this build is just for fetching dependencies
            requestBuilder.withProfiles("native");
        }

        LOG.info("Building {} application project ({})", name, QuarkusConfiguration.isQuarkusNative() ? "native" : "JVM");
        Maven.invoke(requestBuilder.build());
    }

    /**
     * Creates the application skeleton using camel export command.
     */
    private void createUsingJBang() {
        List<String> arguments = new ArrayList<>(List.of(
            "--runtime", "quarkus",
            "--quarkus-group-id", QuarkusConfiguration.quarkusPlatformGroupId(),
            "--quarkus-artifact-id", QuarkusConfiguration.quarkusPlatformArtifactId(),
            "--quarkus-version", QuarkusConfiguration.quarkusPlatformVersion()
        ));

        if (OpenshiftConfiguration.isOpenshift()) {
            arguments.add("--dep");
            arguments.add("io.quarkus:quarkus-openshift");
        }

        super.createUsingJBang(arguments);
    }

    /**
     * Creates the application skeleton using quarkus-maven-plugin.
     */
    private void createWithMaven() {
        LOG.info("Creating Camel Quarkus application project for integration {}", name);

        String quarkusMavenPluginCreate = String.format("%s:%s:%s:create",
            QuarkusConfiguration.quarkusPlatformGroupId(), "quarkus-maven-plugin", QuarkusConfiguration.quarkusPlatformVersion());

        Map<String, String> properties = new HashMap<>(Map.of(
            "projectGroupId", TestConfiguration.appGroupId(),
            "projectArtifactId", name,
            "projectVersion", TestConfiguration.appVersion(),
            "platformGroupId", QuarkusConfiguration.quarkusPlatformGroupId(),
            "platformArtifactId", QuarkusConfiguration.quarkusPlatformArtifactId(),
            "platformVersion", QuarkusConfiguration.quarkusPlatformVersion(),
            "extensions", OpenshiftConfiguration.isOpenshift() ? "openshift" : ""
        ));

        properties.putAll(QuarkusConfiguration.fromSystemProperties());

        Maven.invoke(new BuildRequest.Builder()
            .withBaseDirectory(TestConfiguration.appLocation())
            .withGoals(quarkusMavenPluginCreate)
            .withProperties(properties)
            .withLogFile(getLogPath(Phase.GENERATE))
            .withLogMarker(LogStream.marker(name, Phase.GENERATE))
            .build()
        );

        IntegrationGenerator.createFiles(integrationBuilder, TestConfiguration.appLocation().resolve(name));
    }

    /**
     * Customizes the project generated by quarkus mvn plugin to TNB app needs.
     *
     * @param dependencies dependencies to add to the project
     */
    private void customizeProject(List<Dependency> dependencies) {
        // Remove the GreetingResource.java file that is not used
        final File greetingResource = TestConfiguration.appLocation().resolve(name)
            .resolve("src/main/java/" + TestConfiguration.appGroupId().replace(".", "/") + "/GreetingResource.java").toFile();
        if (greetingResource.exists()) {
            greetingResource.delete();
        }

        // Delete autogenerated tests
        final File tests = TestConfiguration.appLocation().resolve(name).resolve("src/test").toFile();
        try {
            FileUtils.deleteDirectory(tests);
        } catch (IOException e) {
            LOG.warn("Unable to delete {} directory", tests.getAbsolutePath(), e);
        }

        File pom = TestConfiguration.appLocation().resolve(name).resolve("pom.xml").toFile();
        Model model = Maven.loadPom(pom);

        // Append the camel platform bom (quarkus bom already present)
        // Check if the cq bom is already present in the dependencies management, if it is, then it is overriden
        Dependency camelQuarkusBom = model.getDependencyManagement().getDependencies().stream()
            .filter(d -> d.getArtifactId().equals(QuarkusConfiguration.camelQuarkusPlatformArtifactId())).findFirst().orElse(new Dependency());
        model.getDependencyManagement().getDependencies().remove(camelQuarkusBom);

        camelQuarkusBom.setGroupId(QuarkusConfiguration.camelQuarkusPlatformGroupId());
        camelQuarkusBom.setArtifactId(QuarkusConfiguration.camelQuarkusPlatformArtifactId());
        camelQuarkusBom.setVersion(QuarkusConfiguration.camelQuarkusPlatformVersion());
        camelQuarkusBom.setType("pom");
        camelQuarkusBom.setScope("import");
        model.getDependencyManagement().getDependencies().add(camelQuarkusBom);

        if (!OpenshiftConfiguration.isOpenshift()) {
            // quarkus-resteasy is needed for the openshift.yml to be generated, but the resteasy itself is not used anywhere
            // remove quarkus-resteasy-reactive in local deployments as it can throw exceptions for occupied 8080 port
            model.setDependencies(
                model.getDependencies().stream().filter(d -> !"quarkus-resteasy-reactive".equals(d.getArtifactId())).collect(Collectors.toList())
            );
        }

        // Remove default test scope dependencies (junit, rest-assured)
        model.setDependencies(
            model.getDependencies().stream().filter(d -> !"test".equals(d.getScope())).collect(Collectors.toList())
        );

        dependencies.forEach(model.getDependencies()::add);

        Maven.writePom(pom, model);
    }

    @Override
    public void waitUntilReady() {
        super.waitUntilReady();
        if (readinessCheck != null) {
            WaitUtils.waitFor(readinessCheck, 10, 1000L, "Waiting for endpoint readiness");
        }
    }
}
