/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.agent.model;

import java.net.URI;

public class RegistrationInfo {

    private String id;
    private String realm;
    private URI callback;
    private String token;

    RegistrationInfo() {}

    public RegistrationInfo(String id, String realm, URI callback, String token) {
        this.id = id;
        this.realm = realm;
        this.callback = callback;
        this.token = token;
    }

    public String getId() {
        return id;
    }

    public String getRealm() {
        return realm;
    }

    public URI getCallback() {
        return callback;
    }

    public String getToken() {
        return token;
    }

    void setId(String id) {
        this.id = id;
    }

    void setRealm(String realm) {
        this.realm = realm;
    }

    void setCallback(URI callback) {
        this.callback = callback;
    }

    void setToken(String token) {
        this.token = token;
    }
}
