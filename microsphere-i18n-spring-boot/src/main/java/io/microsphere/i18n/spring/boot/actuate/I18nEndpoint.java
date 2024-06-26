/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.microsphere.i18n.spring.boot.actuate;

import io.microsphere.i18n.AbstractResourceServiceMessageSource;
import io.microsphere.i18n.ServiceMessageSource;
import io.microsphere.i18n.spring.DelegatingServiceMessageSource;
import io.microsphere.i18n.spring.PropertySourcesServiceMessageSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cglib.core.Local;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySources;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import static io.microsphere.i18n.spring.constants.I18nConstants.SERVICE_MESSAGE_SOURCE_BEAN_NAME;
import static io.microsphere.i18n.util.I18nUtils.findAllServiceMessageSources;
import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static org.springframework.util.StringUtils.hasText;

/**
 * I18n Spring Boot Actuator Endpoint
 * <pre>
 * {
 * "test.i18n_messages_zh.properties": {
 *
 * },
 * "META-INF/i18n/test/i18n_messages_zh_CN.properties": {
 * "test.a": "测试-a",
 * "test.hello": "您好,{}"
 * },
 * "META-INF/i18n/test/i18n_messages_en.properties": {
 * "test.a": "test-a",
 * "test.hello": "Hello,{}"
 * },
 * "META-INF/i18n/common/i18n_messages_zh_CN.properties": {
 * "common.a": "a"
 * }
 * }
 * </pre>
 *
 * @author <a href="mailto:mercyblitz@gmail.com">Mercy</a>
 * @since 1.0.0
 */
@Endpoint(id = "i18n")
public class I18nEndpoint {

    public static final String PROPERTY_SOURCE_NAME = "i18nEndpointPropertySource";

    private List<ServiceMessageSource> serviceMessageSources;

    @Autowired
    private ConfigurableEnvironment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent(ApplicationReadyEvent event) {
        ConfigurableApplicationContext context = event.getApplicationContext();
        ServiceMessageSource serviceMessageSource = context.getBean(SERVICE_MESSAGE_SOURCE_BEAN_NAME, ServiceMessageSource.class);
        initServiceMessageSources(serviceMessageSource);
    }

    private void initServiceMessageSources(ServiceMessageSource serviceMessageSource) {
        List<ServiceMessageSource> serviceMessageSources = emptyList();
        if (serviceMessageSource instanceof DelegatingServiceMessageSource) {
            DelegatingServiceMessageSource delegatingServiceMessageSource = (DelegatingServiceMessageSource) serviceMessageSource;
            serviceMessageSources = delegatingServiceMessageSource.getDelegate().getServiceMessageSources();
        }

        LinkedList<ServiceMessageSource> allServiceMessageSources = new LinkedList<>();

        int size = serviceMessageSources.size();
        for (int i = 0; i < size; i++) {
            List<ServiceMessageSource> subServiceMessageSources = findAllServiceMessageSources(serviceMessageSources.get(i));
            allServiceMessageSources.addAll(subServiceMessageSources);
        }

        this.serviceMessageSources = allServiceMessageSources;

    }


    @ReadOperation
    public Map<String, Map<String, String>> invoke() {
        List<ServiceMessageSource> serviceMessageSources = this.serviceMessageSources;
        int size = serviceMessageSources.size();
        Map<String, Map<String, String>> allLocalizedResourceMessages = new LinkedHashMap<>(size);
        for (int i = 0; i < size; i++) {
            // FIXME
            ServiceMessageSource serviceMessageSource = serviceMessageSources.get(i);
            if (serviceMessageSource instanceof AbstractResourceServiceMessageSource) {
                AbstractResourceServiceMessageSource resourceServiceMessageSource = (AbstractResourceServiceMessageSource) serviceMessageSource;
                Map<String, Map<String, String>> localizedResourceMessages = resourceServiceMessageSource.getLocalizedResourceMessages();
                localizedResourceMessages.forEach(
                        (k, v) -> allLocalizedResourceMessages.merge(k, v, (oldValue, value) -> value.isEmpty() ? oldValue : value)
                );
            }
        }
        return allLocalizedResourceMessages;
    }

    @ReadOperation
    public Object getMessage(@Selector String code) {
        return getMessage(code, null);
    }

    @ReadOperation
    public List<Map<String, String>> getMessage(@Selector String code, @Selector Locale locale) {
        Set<Locale> supportedLocales = getSupportedLocales(locale);
        List<ServiceMessageSource> serviceMessageSources = this.serviceMessageSources;
        int size = serviceMessageSources.size();
        List<Map<String, String>> messageMaps = new ArrayList<>(size * supportedLocales.size());

        for (int i = 0; i < size; i++) {
            ServiceMessageSource serviceMessageSource = serviceMessageSources.get(i);
            for (Locale supportedLocale : supportedLocales) {
                Map<String, String> messageMap = new LinkedHashMap<>(5);
                String message = serviceMessageSource.getMessage(code, supportedLocale);

                messageMap.put("code", code);
                messageMap.put("source", serviceMessageSource.getSource());

                String resource = getResource(serviceMessageSource, supportedLocale);
                if (hasText(resource)) {
                    messageMap.put("resource", resource);
                }

                if (hasText(message)) {
                    messageMap.put("message", message);
                    messageMap.put("locale", supportedLocale.toString());
                }
                messageMaps.add(messageMap);
            }
        }
        return messageMaps;
    }

    @WriteOperation
    public Map<String, Object> addMessage(String source, Locale locale, String code, String message) throws IOException {
        PropertySourcesServiceMessageSource serviceMessageSource = getPropertySourcesServiceMessageSource(source);
        Properties properties = loadProperties(serviceMessageSource, locale);
        // Add a new code with message
        properties.setProperty(code, message);

        String propertyName = serviceMessageSource.getPropertyName(locale);
        StringWriter stringWriter = new StringWriter();
        // Properties -> StringWriter
        properties.store(stringWriter, "");
        // StringWriter -> String
        String propertyValue = stringWriter.toString();

        MapPropertySource propertySource = getPropertySource();
        Map<String, Object> newProperties = propertySource.getSource();
        newProperties.put(propertyName, propertyValue);

        serviceMessageSource.init();
        return newProperties;
    }

    private Properties loadProperties(PropertySourcesServiceMessageSource serviceMessageSource, Locale locale) throws IOException {
        Properties properties = serviceMessageSource.loadAllProperties(locale);
        return properties == null ? new Properties() : properties;
    }

    private MapPropertySource getPropertySource() {
        MutablePropertySources propertySources = environment.getPropertySources();
        String name = PROPERTY_SOURCE_NAME;
        MapPropertySource propertySource = (MapPropertySource) propertySources.get(name);
        if (propertySource == null) {
            Map<String, Object> properties = new HashMap<>();
            propertySource = new MapPropertySource(name, properties);
            propertySources.addFirst(propertySource);
        }
        return propertySource;
    }

    private PropertySourcesServiceMessageSource getPropertySourcesServiceMessageSource(String source) {
        return serviceMessageSources.stream()
                .filter(serviceMessageSource ->
                        Objects.equals(source, serviceMessageSource.getSource()))
                .filter(this::isPropertySourcesServiceMessageSource)
                .map(PropertySourcesServiceMessageSource.class::cast)
                .findFirst()
                .get();
    }

    private boolean isPropertySourcesServiceMessageSource(ServiceMessageSource serviceMessageSource) {
        return serviceMessageSource instanceof PropertySourcesServiceMessageSource;
    }

    private String getResource(ServiceMessageSource serviceMessageSource, Locale locale) {
        String resource = null;
        if (serviceMessageSource instanceof AbstractResourceServiceMessageSource) {
            AbstractResourceServiceMessageSource resourceServiceMessageSource = (AbstractResourceServiceMessageSource) serviceMessageSource;
            resource = resourceServiceMessageSource.getResource(locale);
        }
        return resource;
    }

    private Set<Locale> getSupportedLocales(Locale locale) {
        if (locale == null) {
            Set<Locale> locales = new LinkedHashSet<>();
            serviceMessageSources.forEach(serviceMessageSource -> {
                locales.addAll(serviceMessageSource.getSupportedLocales());
            });
            return locales;
        } else {
            return singleton(locale);
        }

    }


}
