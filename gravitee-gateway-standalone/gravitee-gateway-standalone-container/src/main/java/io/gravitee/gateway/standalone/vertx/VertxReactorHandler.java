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
package io.gravitee.gateway.standalone.vertx;

import io.gravitee.common.http.IdGenerator;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.reactor.Reactor;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Azize ELAMRANI (azize.elamrani at graviteesource.com)
 * @author GraviteeSource Team
 */
public class VertxReactorHandler implements Handler<HttpServerRequest> {

    private final Reactor reactor;
    private boolean legacyDecodeUrlParams;
    private IdGenerator idGenerator;

    public VertxReactorHandler(final Reactor reactor, boolean legacyDecodeUrlParams, IdGenerator idGenerator) {
        this.reactor = reactor;
        this.legacyDecodeUrlParams = legacyDecodeUrlParams;
        this.idGenerator = idGenerator;
    }

    @Override
    public void handle(HttpServerRequest httpServerRequest) {
        Request request = new VertxHttpServerRequest(httpServerRequest, legacyDecodeUrlParams, idGenerator);
        Response response = new VertxHttpServerResponse(httpServerRequest, request.metrics());

        route(request, response);
    }

    protected void route(final Request request, final Response response) {
        reactor.route(request, response, __ -> {});
    }
}
