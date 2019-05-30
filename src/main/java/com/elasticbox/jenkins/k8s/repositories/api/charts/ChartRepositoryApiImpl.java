/*
 * Copyright 2016 ElasticBox
 *
 * Licensed under the Apache License, Version 2.0, <LICENSE-APACHE or http://apache.org/licenses/LICENSE-2.0>
 * or the MIT license <LICENSE-MIT or http://opensource.org/licenses/MIT> , at your option.
 * This file may not be copied, modified, or distributed except according to those terms.
 */

package com.elasticbox.jenkins.k8s.repositories.api.charts;

import com.google.inject.Inject;

import com.elasticbox.jenkins.k8s.chart.Chart;
import com.elasticbox.jenkins.k8s.chart.ChartDetails;
import com.elasticbox.jenkins.k8s.chart.ChartRepo;
import com.elasticbox.jenkins.k8s.repositories.ChartRepository;
import com.elasticbox.jenkins.k8s.repositories.api.charts.factory.ManifestFactory;
import com.elasticbox.jenkins.k8s.repositories.api.charts.github.GitHubApiContentsService;
import com.elasticbox.jenkins.k8s.repositories.api.charts.github.GitHubApiRawContentDownloadService;
import com.elasticbox.jenkins.k8s.repositories.api.charts.github.GitHubApiResponseContentType;
import com.elasticbox.jenkins.k8s.repositories.api.charts.github.GitHubClientsFactory;
import com.elasticbox.jenkins.k8s.repositories.api.charts.github.GitHubContent;
import com.elasticbox.jenkins.k8s.repositories.error.RepositoryException;

import hudson.Extension;

import org.yaml.snakeyaml.Yaml;
import rx.Observable;
import rx.exceptions.Exceptions;
import rx.functions.Action1;
import rx.functions.Func1;

import java.util.ArrayList;
import java.util.List;

@Extension
public class ChartRepositoryApiImpl implements ChartRepository {

    @Inject
    GitHubClientsFactory clientsFactory;

    @Override
    public List<String> chartNames(final ChartRepo repo) throws RepositoryException {
        return  chartNames(repo, null);
    }

    @Override
    public List<String> chartNames(final ChartRepo repo, String ref) throws RepositoryException {

        final List<String> chartNames = new ArrayList<>();

        // remove next if block sentences when upgrade this plugin to allow operations in new repository:
        // https://github.com/helm/charts/tree/master/stable
        if (repo.getUrl().relativeToRepoPathInCaseOfRepoUrl().length() > 0) {
            throw new RepositoryException("Repository not supported in this plugin version");
        }

        String defaultRef = (ref == null || ref.equals("")) ? "master" : ref;

        final GitHubApiContentsService client = getClient(repo, repo.getUrl().toString(), GitHubApiContentsService
            .class, GitHubApiResponseContentType.JSON);

        client.content(repo.getUrl().ownerInCaseOfRepoUrl(), repo.getUrl().repoInCaseOfRepoUrl(),
                        repo.getUrl().relativeToRepoPathInCaseOfRepoUrl(), defaultRef)
            .flatMap(new Func1<List<GitHubContent>, Observable<GitHubContent>>() {
                @Override
                public Observable<GitHubContent> call(List<GitHubContent> gitHubContents) {
                    return Observable.from(gitHubContents);
                }
            })
            .filter(new Func1<GitHubContent, Boolean>() {
                @Override
                public Boolean call(GitHubContent gitHubContent) {
                    return gitHubContent.getType().equals("dir");
                }
            })
            .subscribe(new Action1<GitHubContent>() {
                @Override
                public void call(GitHubContent gitHubContent) {
                    chartNames.add(gitHubContent.getName());
                }
            });

        return chartNames;

    }

    @Override
    public Chart chart(final ChartRepo repo, String chartName) throws RepositoryException {
        return chart(repo, chartName, null);
    }

    //TODO study how to do that using an specific worker
    @Override
    public Chart chart(final ChartRepo repo, String chartName, String ref) throws RepositoryException {

        final Chart.ChartBuilder chartBuilder = new Chart.ChartBuilder();

        final String defaultRef = (ref == null || ref.equals("")) ? "master" : ref;

        final GitHubApiContentsService client = getClient(repo, repo.getUrl().toString(), GitHubApiContentsService
            .class, GitHubApiResponseContentType.JSON);

        client.content(repo.getUrl().ownerInCaseOfRepoUrl(), repo.getUrl().repoInCaseOfRepoUrl(), chartName, defaultRef)
            .flatMap(new Func1<List<GitHubContent>, Observable<GitHubContent>>() {
                @Override
                public Observable<GitHubContent> call(List<GitHubContent> gitHubContents) {
                    return Observable.from(gitHubContents);
                }
            })
            .subscribe(new Action1<GitHubContent>() {
                @Override
                public void call(GitHubContent gitHubContent) {

                    if (gitHubContent.getName().equals("Chart.yaml")) {
                        final String chartUrl = gitHubContent.getDownloadUrl();
                        //add the general details to the chart
                        try {
                            chartDetails(repo, chartUrl, chartBuilder);
                        } catch (RepositoryException e) {
                            Exceptions.propagate(e);
                        }
                    } else if (gitHubContent.getType().equals("dir")
                                && gitHubContent.getName().equals("manifests")) {
                            //retrieve the contained yaml files

                        try {
                            manifests(repo, gitHubContent.getUrl(), chartBuilder);
                        } catch (RepositoryException e) {
                            Exceptions.propagate(e);
                        }
                    }
                }
            });

        return chartBuilder.build();

    }

    private void manifests(final ChartRepo repo, String manifestsListUrl, final Chart.ChartBuilder chartBuilder) throws
        RepositoryException {

        final GitHubApiContentsService client = getClient(repo, manifestsListUrl, GitHubApiContentsService
            .class, GitHubApiResponseContentType.JSON);

        client.content(manifestsListUrl)
            .flatMap(new Func1<List<GitHubContent>, Observable<GitHubContent>>() {
                @Override
                public Observable<GitHubContent> call(List<GitHubContent> gitHubContents) {
                    return Observable.from(gitHubContents);
                }
            })
            .subscribe(new Action1<GitHubContent>() {
                @Override
                public void call(GitHubContent gitHubContent) {
                    final String manifestUrl = gitHubContent.getDownloadUrl();
                    try {
                        manifest(repo, manifestUrl, chartBuilder);
                    } catch (RepositoryException e) {
                        Exceptions.propagate(e);
                    }
                }
            });


    }

    private void manifest(ChartRepo repo, String manifestContentUrl, final Chart.ChartBuilder chartBuilder)
        throws RepositoryException {

        final GitHubApiRawContentDownloadService client = getClient(repo, manifestContentUrl,
            GitHubApiRawContentDownloadService.class, GitHubApiResponseContentType.RAW_STRING);

        client.rawContent(manifestContentUrl).subscribe(
            new Action1<String>() {
                @Override
                public void call(String yaml) {
                    try {
                        ManifestFactory.addManifest(yaml, chartBuilder);
                    } catch (RepositoryException e) {
                        chartBuilder.addError(e);
                    }
                }
            }
        );

    }

    private <T> void chartDetails(ChartRepo repo, String chartDetailsContentUrl, final Chart.ChartBuilder chartBuilder)
        throws RepositoryException {


        final GitHubApiRawContentDownloadService client = getClient(repo, chartDetailsContentUrl,
            GitHubApiRawContentDownloadService.class, GitHubApiResponseContentType.RAW_STRING);

        final Yaml yaml = new Yaml();
        client.rawContent(chartDetailsContentUrl)
            .map(new Func1<String, ChartDetails>() {
                @Override
                public ChartDetails call(String yamlString) {
                    final ChartDetails chartDetails = yaml.loadAs(yamlString, ChartDetails.class);
                    return chartDetails;
                }
            }).subscribe(new Action1<ChartDetails>() {
                @Override
                public void call(ChartDetails details) {
                    chartBuilder.chartDetails(details);
                }
            });

    }

    private <T> T getClient(ChartRepo repo, String url, Class<T> serviceClass, GitHubApiResponseContentType resType)
        throws RepositoryException {

        return clientsFactory.getClient(repo, serviceClass, resType);
    }

    public GitHubClientsFactory getClientsFactory() {
        return clientsFactory;
    }

    public void setClientsFactory(GitHubClientsFactory clientsFactory) {
        this.clientsFactory = clientsFactory;
    }
}
