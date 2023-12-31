/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.gateway.core.invoker;

import io.gravitee.common.http.HttpMethod;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.util.MultiValueMap;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Invoker;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.proxy.ProxyConnection;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.gateway.api.proxy.builder.ProxyRequestBuilder;
import io.gravitee.gateway.api.stream.ReadStream;
import io.gravitee.gateway.core.endpoint.resolver.EndpointResolver;
import io.gravitee.gateway.core.logging.LimitedLoggableProxyConnection;
import io.gravitee.gateway.core.logging.LoggableProxyConnection;
import io.gravitee.gateway.core.logging.utils.LoggingUtils;
import io.gravitee.gateway.core.proxy.DirectProxyConnection;
import io.netty.handler.codec.http.QueryStringEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class EndpointInvoker implements Invoker {

    private static final String URI_PARAM_SEPARATOR = "&";
    private static final char URI_PARAM_SEPARATOR_CHAR = '&';
    private static final char URI_PARAM_VALUE_SEPARATOR_CHAR = '=';
    private static final char URI_QUERY_DELIMITER_CHAR = '?';
    private static final CharSequence URI_QUERY_DELIMITER_CHAR_SEQUENCE = "?";

    @Value("${legacy.decode-url-params:false}")
    private boolean legacyDecodeUrlParams;

    @Autowired
    private EndpointResolver endpointResolver;

    @Override
    public void invoke(ExecutionContext context, ReadStream<Buffer> stream, Handler<ProxyConnection> connectionHandler) {
        EndpointResolver.ResolvedEndpoint endpoint = endpointResolver.resolve(context.request(), context);

        // Endpoint can be null if none endpoint can be selected or if the selected endpoint is unavailable
        if (endpoint == null) {
            DirectProxyConnection statusOnlyConnection = new DirectProxyConnection(HttpStatusCode.SERVICE_UNAVAILABLE_503);
            connectionHandler.handle(statusOnlyConnection);
            statusOnlyConnection.sendResponse();
        } else {
            URI uri = null;
            try {
                if (legacyDecodeUrlParams) {
                    uri = legacyEncodeQueryParameters(endpoint.getUri(), context.request().parameters());
                } else {
                    uri = buildURI(endpoint.getUri(), context);
                }
            } catch (Exception ex) {
                context.request().metrics().setMessage(getStackTraceAsString(ex));

                // Request URI is not correct nor correctly encoded, returning a bad request
                DirectProxyConnection statusOnlyConnection = new DirectProxyConnection(HttpStatusCode.BAD_REQUEST_400);
                connectionHandler.handle(statusOnlyConnection);
                statusOnlyConnection.sendResponse();
            }

            if (uri != null) {

                ProxyRequest proxyRequest = ProxyRequestBuilder.from(context.request())
                        .uri(uri)
                        .method(setHttpMethod(context))
                        .rawMethod(context.request().rawMethod())
                        .headers(context.request().headers())
                        .build();

                ProxyConnection proxyConnection = endpoint.getConnector().request(proxyRequest);

                // Enable logging at proxy level
                Object loggingAttr = context.getAttribute(ExecutionContext.ATTR_PREFIX + "logging.proxy");
                if (loggingAttr != null && ((boolean) loggingAttr)) {
                    int maxSizeLogMessage = LoggingUtils.getMaxSizeLogMessage(context);
                    proxyConnection = maxSizeLogMessage == -1 ?
                            new LoggableProxyConnection(proxyConnection, proxyRequest, context) :
                            new LimitedLoggableProxyConnection(proxyConnection, proxyRequest, context, maxSizeLogMessage);
                }

                connectionHandler.handle(proxyConnection);

                // Plug underlying stream to connection stream
                ProxyConnection finalProxyConnection = proxyConnection;

                stream
                        .bodyHandler(buffer -> {
                            finalProxyConnection.write(buffer);

                            if (finalProxyConnection.writeQueueFull()) {
                                context.request().pause();
                                finalProxyConnection.drainHandler(aVoid -> context.request().resume());
                            }
                        })
                        .endHandler(aVoid -> finalProxyConnection.end());
            }
        }

        // Resume the incoming request to handle content and end
        context.request().resume();
    }

    private URI legacyEncodeQueryParameters(String uri, MultiValueMap<String, String> parameters) throws URISyntaxException {
        if (parameters != null && !parameters.isEmpty()) {
            QueryStringEncoder encoder = new QueryStringEncoder(uri);
            for (Map.Entry<String, List<String>> queryParam : parameters.entrySet()) {
                if (queryParam.getValue() != null) {
                    for (String value : queryParam.getValue()) {
                        encoder.addParam(queryParam.getKey(), (value != null && !value.isEmpty()) ? value : null);

                    }
                }
            }
            return encoder.toUri();
        }
        return URI.create(uri);
    }

    private URI buildURI(String uri, ExecutionContext executionContext) {
        MultiValueMap<String, String> parameters = executionContext.request().parameters();

        if (parameters == null || parameters.isEmpty()) {
            return URI.create(uri);
        }

        return addQueryParameters(uri, parameters);
    }

    private URI addQueryParameters(String uri, MultiValueMap<String, String> parameters) {
        StringJoiner parametersAsString = new StringJoiner(URI_PARAM_SEPARATOR);
        parameters.forEach( (paramName, paramValues) -> {
            if (paramValues != null) {
                for (String paramValue: paramValues) {
                    if (paramValue == null) {
                        parametersAsString.add(paramName);
                    } else {
                        parametersAsString.add(paramName + URI_PARAM_VALUE_SEPARATOR_CHAR + paramValue);
                    }
                }
            }
        });

        if (uri.contains(URI_QUERY_DELIMITER_CHAR_SEQUENCE)) {
            return URI.create(uri + URI_PARAM_SEPARATOR_CHAR + parametersAsString.toString());
        } else {
            return URI.create(uri + URI_QUERY_DELIMITER_CHAR + parametersAsString.toString());

        }
    }

    private HttpMethod setHttpMethod(ExecutionContext context) {
        io.gravitee.common.http.HttpMethod overrideMethod = (io.gravitee.common.http.HttpMethod)
                context.getAttribute(ExecutionContext.ATTR_REQUEST_METHOD);
        return (overrideMethod == null) ? context.request().method() : overrideMethod;
    }

    private static String getStackTraceAsString(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }
}
