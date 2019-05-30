/*
 * Copyright 2016 ElasticBox
 *
 * Licensed under the Apache License, Version 2.0, <LICENSE-APACHE or http://apache.org/licenses/LICENSE-2.0>
 * or the MIT license <LICENSE-MIT or http://opensource.org/licenses/MIT> , at your option.
 * This file may not be copied, modified, or distributed except according to those terms.
 */

package com.elasticbox.jenkins.k8s.plugin.clouds;

import com.google.inject.Inject;
import com.google.inject.Injector;

import com.elasticbox.jenkins.k8s.auth.Authentication;
import com.elasticbox.jenkins.k8s.plugin.slaves.KubernetesSlave;
import com.elasticbox.jenkins.k8s.repositories.KubernetesRepository;
import com.elasticbox.jenkins.k8s.repositories.api.kubeclient.KubernetesClientFactory;
import com.elasticbox.jenkins.k8s.repositories.error.RepositoryException;
import com.elasticbox.jenkins.k8s.services.SlaveProvisioningService;
import com.elasticbox.jenkins.k8s.services.error.ServiceException;
import com.elasticbox.jenkins.k8s.util.PluginHelper;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.net.ssl.SSLHandshakeException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

public class KubernetesCloud extends AbstractCloudImpl {

    private static final Logger LOGGER = Logger.getLogger(KubernetesCloud.class.getName() );

    public static final String NAME_PREFIX = "KubeCloud-";

    private final String displayName;
    private final String credentialsId;
    private final KubernetesCloudParams kubeCloudParams;
    private final List<ChartRepositoryConfig> chartRepositoryConfigurations;
    private final List<PodSlaveConfig> podSlaveConfigurations;

    @Inject
    transient KubernetesClientFactory kubeFactory;

    @Inject
    transient SlaveProvisioningService slaveProvisioningService;

    @DataBoundConstructor
    public KubernetesCloud(String name, String displayName, String endpointUrl, String predefinedNamespace,
                           String maxContainers, String credentialsId, String serverCert,
                           List<ChartRepositoryConfig> chartRepositoryConfigurations,
                           List<PodSlaveConfig> podSlaveConfigurations) {

        super( (StringUtils.isNotEmpty(name) ) ? name : NAME_PREFIX + UUID.randomUUID().toString(), maxContainers );
        this.displayName = displayName;
        this.credentialsId = credentialsId;

        // Passing !disableCertCheck because it is with 'negative' property in jelly (opposite behaviour)
        this.kubeCloudParams = new KubernetesCloudParams(endpointUrl, predefinedNamespace,
                PluginHelper.getAuthenticationData(credentialsId), serverCert);

        this.chartRepositoryConfigurations = chartRepositoryConfigurations;
        this.podSlaveConfigurations = podSlaveConfigurations;

        injectMembers();
        if (StringUtils.isNotEmpty(name) ) {
            kubeFactory.resetKubernetesClient(name);
        }
    }

    public static List<KubernetesCloud> getKubernetesClouds() {
        List<KubernetesCloud> clouds = new ArrayList<>();

        final Jenkins instance = Jenkins.get();
        if (instance != null) {
            for (Cloud cloud : instance.clouds) {
                if (cloud instanceof KubernetesCloud) {
                    clouds.add( (KubernetesCloud) cloud);
                }
            }
        }
        return clouds;
    }

    public static KubernetesCloud getKubernetesCloud(String kubeName) {
        final Jenkins instance = Jenkins.get();

        if (instance == null) {
            return null;
        }
        final Cloud cloud = instance.getCloud(kubeName);
        return (cloud != null && cloud instanceof KubernetesCloud) ? (KubernetesCloud)cloud : null;
    }

    public String getName() {
        return name;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    public String getPredefinedNamespace() {
        return kubeCloudParams.getNamespace();
    }

    public String getEndpointUrl() {
        return kubeCloudParams.getEndpointUrl();
    }

    public String getMaxContainers() {
        return super.getInstanceCapStr();
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public List<ChartRepositoryConfig> getChartRepositoryConfigurations() {
        return chartRepositoryConfigurations;
    }

    public List<PodSlaveConfig> getPodSlaveConfigurations() {
        return podSlaveConfigurations;
    }

    public ChartRepositoryConfig getChartRepositoryConfiguration(String chartsRepo) {
        for (ChartRepositoryConfig config : getChartRepositoryConfigurations() ) {
            if (config.getDescription().equals(chartsRepo)) {
                return config;
            }
        }
        return null;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(final Label label, int excessWorkload) {

        LOGGER.info("Slave provisioning requested for label '" + label + "', excess workload: " + excessWorkload);

        final List<PodSlaveConfigurationParams> podSlaveConfigurationParams = new ArrayList<>();
        for (PodSlaveConfig config: podSlaveConfigurations) {
            podSlaveConfigurationParams.add(config.getPodSlaveConfigurationParams());
        }

        List<NodeProvisioner.PlannedNode> plannedSlavesToDeploy = new ArrayList<NodeProvisioner.PlannedNode>();

        for (int i = 1; i <= excessWorkload; i++) {
            plannedSlavesToDeploy.add(
                new NodeProvisioner.PlannedNode(
                    KubernetesSlave.DESCRIPTION,
                    Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                        @Override
                        public Node call() throws Exception {
                            KubernetesSlave slave = slaveProvisioningService.slaveProvision(
                                KubernetesCloud.this,
                                podSlaveConfigurationParams,
                                label);

                            return slave;
                        }
                    }),
                    1)
            );
        }

        return plannedSlavesToDeploy;

    }

    @Override
    public boolean canProvision(Label label) {

        LOGGER.info("Checking label: " + label);

        final List<PodSlaveConfigurationParams> podSlaveConfigurationParams = new ArrayList<>();
        for (PodSlaveConfig config: podSlaveConfigurations) {
            podSlaveConfigurationParams.add(config.getPodSlaveConfigurationParams());
        }

        try {
            FormValidation testConnection = ((DescriptorImpl) getDescriptor()).doTestConnection(
                    getEndpointUrl(), getPredefinedNamespace(), getCredentialsId(), null);

            if (testConnection.kind == FormValidation.Kind.ERROR) {
                LOGGER.warning("Unable to connect to: " + this + testConnection.getMessage() );
                return false;
            }

            return slaveProvisioningService.canProvision(KubernetesCloud.this, podSlaveConfigurationParams, label);

        } catch (ServiceException exception) {
            LOGGER.info("Error while checking if provision is allowed");
            return false;
        }
    }


    public KubernetesCloudParams getKubernetesCloudParams() {
        return kubeCloudParams;
    }

    @Override
    public String toString() {
        return "KubernetesCloud [" + getDisplayName() + "@" + kubeCloudParams.getEndpointUrl() + "]";
    }

    protected Object readResolve() {
        injectMembers();
        return this;
    }

    private void injectMembers() {
        if (kubeFactory == null) {
            ( (DescriptorImpl) getDescriptor() ).injector.injectMembers(this);
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        private static final String KUBERNETES_CLOUD = "Kubernetes cloud";

        @Inject
        private Injector injector;

        @Inject
        KubernetesRepository kubeRepository;

        @Override
        public String getDisplayName() {
            return KUBERNETES_CLOUD;
        }

        public Injector getInjector() {
            return injector;
        }

        public ListBoxModel doFillPredefinedNamespaceItems(@QueryParameter String endpointUrl,
                                                 @QueryParameter String credentialsId,
                                                 @QueryParameter String serverCert) {

            if (StringUtils.isEmpty(endpointUrl) ) {
                return PluginHelper.doFillNamespaceItems(null);
            }

            Authentication authData = PluginHelper.getAuthenticationData(credentialsId);
            final KubernetesCloudParams kubeCloudParams =
                new KubernetesCloudParams(endpointUrl, null, authData, serverCert);

            return PluginHelper.doFillNamespaceItems(kubeRepository.getNamespaces(kubeCloudParams) );
        }

        @RequirePOST
        public FormValidation doTestConnection(@QueryParameter String endpointUrl,
                                               @QueryParameter String namespace,
                                               @QueryParameter String credentialsId,
                                               @QueryParameter String serverCert) {

            if (StringUtils.isEmpty(endpointUrl) ) {
                return FormValidation.error("Required fields not provided");
            }

            Authentication authData = PluginHelper.getAuthenticationData(credentialsId);

            final KubernetesCloudParams kubeCloudParams = new KubernetesCloudParams(
                endpointUrl, namespace, authData, serverCert);
            kubeCloudParams.setDisableCertCheck(false);

            try {
                if (kubeRepository.testConnection(kubeCloudParams) ) {
                    if (LOGGER.isLoggable(Level.CONFIG) ) {
                        LOGGER.config("Connection successful to Kubernetes Cloud at: " + endpointUrl);
                    }
                    return FormValidation.ok("Connection successful");
                } else {
                    LOGGER.severe("Unable to connect to Kubernetes clouds at: " + endpointUrl);
                    return FormValidation.error("Connection error");
                }
            } catch (RepositoryException excep) {
                String msg = "Connection error - " + excep.getCausedByMessages();
                if (excep.getCause() != null && excep.getCause().getCause() instanceof SSLHandshakeException) {
                    kubeCloudParams.setDisableCertCheck(true);
                    try {
                        if (kubeRepository.testConnection(kubeCloudParams) ) {
                            if (LOGGER.isLoggable(Level.CONFIG) ) {
                                LOGGER.config("Connection successful disabling Cert check to Kubernetes Cloud at: "
                                    + endpointUrl);
                            }
                            return FormValidation.warning(
                                "Warning: Connection successful but the server certificate is not trusted");
                        }
                    } catch (RepositoryException exception) {
                        msg = "Connection error - " + exception.getCausedByMessages();
                    }
                }
                LOGGER.severe(msg);
                return FormValidation.error(msg);
            }
        }

        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String endpointUrl) {
            return PluginHelper.doFillCredentialsIdItems(endpointUrl);
        }

        public FormValidation doCheckEndpointUrl(@QueryParameter String endpointUrl) {
            if (StringUtils.isBlank(endpointUrl)) {
                return FormValidation.error("Endpoint Url is required");
            }
            return FormValidation.ok();
        }
    }
}
