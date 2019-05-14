/*
 * Copyright 2016 ElasticBox
 *
 * Licensed under the Apache License, Version 2.0, <LICENSE-APACHE or http://apache.org/licenses/LICENSE-2.0>
 * or the MIT license <LICENSE-MIT or http://opensource.org/licenses/MIT> , at your option.
 * This file may not be copied, modified, or distributed except according to those terms.
 */

package com.elasticbox.jenkins.k8s.repositories.api.charts.github;

import org.apache.commons.lang.StringUtils;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;

/**
 * Created by serna on 4/14/16.
 */
public class GitHubUrl {

    private String relativeToRepoPath = "";
    private String baseUrl;
    private URI parsedUrl;

    public GitHubUrl(String url)  {
        this.baseUrl =  normalize(url);
        try {
            this.parsedUrl = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw  new RuntimeException("Malformed URL: " + url);
        }
    }

    private  String normalize(String url) {

        if (StringUtils.isBlank(url)) {
            return null;
        }
        int pos = url.indexOf("/tree/");
        if ( pos >= 0) {
            initRelativeToRepoPath( url.substring(pos) );
            // Strip "/tree/..."
            url = url.substring(0, pos);
        }
        if (!url.endsWith("/")) {
            url += '/';
        }
        return url;
    }

    private void initRelativeToRepoPath(String branchPath) {
        // Gets string after second slash "/tree/<branch>/..."
        int fromIndex = 0;
        int slashOrder = 2;
        do {
            fromIndex = branchPath.indexOf("/", fromIndex + 1);
            slashOrder-- ;
        } while ((fromIndex >= 0) && (slashOrder > 0)) ;

        if (slashOrder == 0 ) {
            if (branchPath.endsWith("/")) {
                relativeToRepoPath = branchPath.substring(fromIndex + 1, branchPath.length() - 1);
            } else {
                relativeToRepoPath = branchPath.substring(fromIndex + 1 );
            }

        }
    }

    public String protocol() {
        return parsedUrl.getScheme();
    }

    public String host() {
        return parsedUrl.getHost();
    }

    public int port() {
        return parsedUrl.getPort();
    }

    public String getHostAndPortTogether() {
        StringBuilder builder = new StringBuilder(this.parsedUrl.getScheme());
        builder.append("://");
        builder.append(this.parsedUrl.getHost());
        if (this.parsedUrl.getPort() > 0) {
            builder.append(":");
            builder.append(this.parsedUrl.getPort());
        }
        builder.append("/");
        return builder.toString();
    }


    public String ownerInCaseOfRepoUrl() {
        final String[] split = parsedUrl.getPath().split("/");
        if (split.length > 1 ) {
            return split[1];
        }
        return null;
    }

    public String repoInCaseOfRepoUrl() {
        final String[] split = parsedUrl.getPath().split("/");
        if (split.length > 2 ) {
            return split[2];
        }
        return null;
    }

    public String relativeToRepoPathInCaseOfRepoUrl() {
        return this.relativeToRepoPath;
    }

    public String path() {
        final String path = parsedUrl.getPath();
        if (path.charAt(path.length() - 1) == '/') {
            return path.substring(1,path.length() - 1);
        }
        return path.substring(1);
    }

    public String [] pathAsArray() {
        final String[] split = parsedUrl.getPath().split("/");
        return Arrays.copyOfRange(split,1,split.length - 1);
    }

    public String query() {
        final String query = this.parsedUrl.getQuery();
        if (query != null && query.charAt(query.length() - 1) == '/') {
            return query.substring(0,query.length() - 1);
        }
        return query;

    }


    public String commitId(final String id) {
        return new StringBuilder().append(baseUrl).append("commit/").append(id).toString();
    }

    @Override
    public String toString() {
        return this.baseUrl;
    }

}
