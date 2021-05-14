/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 * The Federal Office of Administration (Bundesverwaltungsamt, BVA)
 * licenses this file to you under the Apache License, Version 2.0 (the
 * License). You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package de.bund.bva.isyfact.aufrufkontext.impl;

import static de.bund.bva.isyfact.aufrufkontext.common.konstanten.MatcherKonstanten.BEARER_MATCHER;

import de.bund.bva.isyfact.aufrufkontext.AufrufKontext;
import de.bund.bva.isyfact.aufrufkontext.AufrufKontextVerwalter;

/**
 * Provides the call context to an application.
 * @param <T> the call context that the class manages.
 */
public class AufrufKontextVerwalterImpl<T extends AufrufKontext> implements AufrufKontextVerwalter<T> {

    /** The current call context ('AufrufKontext' object). */
    private T aufrufKontext;

    /** The current OAuth2 Bearer Token. */
    private String bearerToken;

    @Override
    public T getAufrufKontext() {
        return aufrufKontext;
    }

    @Override
    public void setAufrufKontext(T aufrufKontext) {
        this.aufrufKontext = aufrufKontext;
    }

    public String getBearerToken() {
        return this.bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken == null ? null : bearerToken.replaceFirst(BEARER_MATCHER, "");
    }

}
