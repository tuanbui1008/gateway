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
package io.gravitee.gateway.handlers.api.validator;

import io.gravitee.gateway.handlers.api.definition.Api;

/**
 * @author David BRASSELY (david at gravitee.io)
 * @author GraviteeSource Team
 */
public class ApiValidator implements Validator {

    @Override
    public void validate(Api definition) {
        if (definition.getName() == null || definition.getName().isEmpty()) {
            throw new ValidationException("An API must have a name");
        }

        if (definition.getPaths() == null || definition.getPaths().isEmpty()) {
            throw new ValidationException("An API must have, at least, one path defined (default is /*)");
        }
    }
}
