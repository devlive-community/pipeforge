/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.web;

import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the Web UI from {@code classpath:/ui/}. Paths that are not files (no extension) are
 * client-side routes and get {@code index.html}; {@code api/} paths are never served from here,
 * so an unknown endpoint is a 404 error object rather than the UI.
 */
@Configuration(proxyBeanMethods = false)
public class WebUiConfiguration implements WebMvcConfigurer {

    static final String LOCATION = "ui/";

    /** The root path is not a resource path, so it is forwarded to the index page explicitly. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/" + LOCATION)
                .resourceChain(true)
                .addResolver(new SpaResolver());
    }

    /** Resolves files of the UI, and index.html for client-side routes. */
    static final class SpaResolver extends PathResourceResolver {
        @Override
        protected @Nullable Resource getResource(String path, Resource location) throws IOException {
            if (path.equals("api") || path.startsWith("api/")) {
                return null;
            }
            Resource resource = location.createRelative(path);
            if (!path.isEmpty() && resource.exists() && resource.isReadable()) {
                return resource;
            }
            String last = path.substring(path.lastIndexOf('/') + 1);
            if (last.contains(".")) {
                return null;
            }
            Resource index = new ClassPathResource(LOCATION + "index.html");
            return index.exists() ? index : null;
        }
    }
}
