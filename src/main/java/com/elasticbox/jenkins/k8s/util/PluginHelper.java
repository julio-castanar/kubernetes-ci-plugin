/*
 * Copyright 2016 ElasticBox
 *
 * Licensed under the Apache License, Version 2.0, <LICENSE-APACHE or http://apache.org/licenses/LICENSE-2.0>
 * or the MIT license <LICENSE-MIT or http://opensource.org/licenses/MIT> , at your option.
 * This file may not be copied, modified, or distributed except according to those terms.
 */

package com.elasticbox.jenkins.k8s.util;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.elasticbox.jenkins.k8s.auth.Authentication;
import com.elasticbox.jenkins.k8s.auth.TokenAuthentication;
import com.elasticbox.jenkins.k8s.auth.UserAndPasswordAuthentication;
import com.elasticbox.jenkins.k8s.chart.ChartRepo;
import com.elasticbox.jenkins.k8s.plugin.auth.TokenCredentialsImpl;
import hudson.ProxyConfiguration;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.QueryParameter;

import java.util.Collections;
import java.util.List;

public class PluginHelper {

    public static final String DEFAULT_NAMESPACE = "default";

    private static final ListBoxModel.Option OPTION_CHOOSE_CHART =
            new ListBoxModel.Option("--Please choose the chart to use--", StringUtils.EMPTY);

    private static final ListBoxModel.Option OPTION_CHOOSE_NAMESPACE =
            new ListBoxModel.Option("--Please choose the namespace--", StringUtils.EMPTY);

    public static final ListBoxModel.Option OPTION_CHOOSE_CLOUD =
            new ListBoxModel.Option("--Please choose your cloud--", StringUtils.EMPTY);

    public static final ListBoxModel.Option OPTION_CHOOSE_CHART_REPO_CONFIG =
            new ListBoxModel.Option("--Please choose your chart repository configuration--", StringUtils.EMPTY);

    public static boolean anyOfThemIsBlank(String... inputParameters) {
        for (String inputParameter : inputParameters) {
            if (StringUtils.isBlank(inputParameter)) {
                return true;
            }
        }
        return false;
    }

    public static void addAllPairs(ListBoxModel listBoxItems, List<KeyValuePair<String, String>> newItems) {
        for (KeyValuePair<String, String> pair: newItems) {
            listBoxItems.add(pair.getValue(), pair.getKey() );
        }
    }

    public static ListBoxModel addAllItems(ListBoxModel listBoxItems, List<String> newItems) {
        for (String item: newItems) {
            listBoxItems.add(item);
        }
        return listBoxItems;
    }

    public static ListBoxModel doFillChartItems(List<String> chartsList) {
        ListBoxModel items = new ListBoxModel(OPTION_CHOOSE_CHART);
        if ( chartsList != null ) {
            PluginHelper.addAllItems(items, chartsList);
        }
        return items;
    }

    public static ListBoxModel doFillNamespaceItems(List<String> namespaces) {
        ListBoxModel items = new ListBoxModel(OPTION_CHOOSE_NAMESPACE);
        if ( namespaces != null ) {
            PluginHelper.addAllItems(items, namespaces);
        }
        return items;
    }

    public static Authentication getAuthenticationData(String credentialsId) {
        if (StringUtils.isBlank(credentialsId) ) {
            return null;
        }
        Authentication authData = null;
        final StandardCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(StandardCredentials.class, Jenkins.get(),
                        ACL.SYSTEM, Collections.<DomainRequirement>emptyList() ),
                CredentialsMatchers.withId(credentialsId) );

        if (credentials instanceof TokenCredentialsImpl) {
            TokenCredentialsImpl tokenCredentials = (TokenCredentialsImpl)credentials;
            authData = new TokenAuthentication(tokenCredentials.getSecret().getPlainText() );

        } else if (credentials instanceof UsernamePasswordCredentials) {
            UsernamePasswordCredentials userPw = (UsernamePasswordCredentials)credentials;
            authData = new UserAndPasswordAuthentication(userPw.getUsername(),
                    userPw.getPassword().getPlainText() );
        }
        return authData;
    }


    public static ListBoxModel doFillCredentialsIdItems(@QueryParameter String endpointUrl) {

        StandardListBoxModel listBoxModel = (StandardListBoxModel) new StandardListBoxModel();
        listBoxModel.includeEmptyValue();

        // Important! Otherwise you expose credentials metadata to random web requests.
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return listBoxModel;
        }

        CredentialsMatcher matcher = CredentialsMatchers.anyOf(
                CredentialsMatchers.instanceOf(TokenCredentialsImpl.class),
                CredentialsMatchers.instanceOf(UsernamePasswordCredentials.class));

        List<DomainRequirement> domainRequirements = URIRequirementBuilder.fromUri(endpointUrl).build();

        listBoxModel.includeMatchingAs(
                ACL.SYSTEM,
                Jenkins.get(),
                StandardCredentials.class,
                domainRequirements,
                matcher );

        return listBoxModel;

    }


    public static ChartRepo getChartRepoData(String chartsRepoUrl, String credentialsId) {
        Authentication authData = PluginHelper.getAuthenticationData(credentialsId);
        ChartRepo chartRepo = new ChartRepo(chartsRepoUrl, authData);

        final ProxyConfiguration proxyConfig = Jenkins.get().proxy;
        if (proxyConfig != null) {
            chartRepo.setProxy(proxyConfig.createProxy(chartsRepoUrl) );

            if (StringUtils.isNotEmpty(proxyConfig.getUserName() )) {
                UserAndPasswordAuthentication proxyAuth =
                        new UserAndPasswordAuthentication(proxyConfig.getUserName(), proxyConfig.getPassword() );
                chartRepo.setProxyAuthentication(proxyAuth);
            }
        }
        return chartRepo;
    }
}
