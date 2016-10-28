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
package de.bund.bva.pliscommon.polling.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;

import org.springframework.beans.factory.InitializingBean;

import de.bund.bva.isyfact.logging.IsyLogger;
import de.bund.bva.isyfact.logging.IsyLoggerFactory;
import de.bund.bva.pliscommon.konfiguration.common.Konfiguration;
import de.bund.bva.pliscommon.polling.PollingVerwalter;
import de.bund.bva.pliscommon.polling.common.exception.PollingClusterKonfigurationException;
import de.bund.bva.pliscommon.polling.common.exception.PollingClusterUnbekanntException;
import de.bund.bva.pliscommon.polling.common.exception.PollingUeberpruefungTechnicalException;
import de.bund.bva.pliscommon.polling.common.konstanten.EreignisSchluessel;
import de.bund.bva.pliscommon.polling.common.konstanten.Fehlerschluessel;
import de.bund.bva.pliscommon.polling.common.konstanten.KonfigurationSchluessel;

/**
 * Implementierung der Komponente PollingVerwalter.
 *
 */
public class PollingVerwalterImpl implements PollingVerwalter, InitializingBean {

    /** Der Logger dieser Klasse. */
    private static final IsyLogger LOG = IsyLoggerFactory.getLogger(PollingVerwalter.class);

    /** Name des MBean-Attributs "ZeitraumLetztePollingAktivitaet". */
    private static final String ZEITRAUM_LETZTE_POLLING_AKTIVITAET = "ZeitraumLetztePollingAktivitaet";

    /** Liste der verwalteten Polling-Cluster. */
    private Map<String, PollingCluster> pollingClusterMap;

    /** Zugriff auf die Komponente Konfiguration. Wird von Spring gesetzt. */
    private Konfiguration konfiguration;

    /** Domain-Name für die JMX-MBeans. Kann über Spring gesetzt werden. */
    private String jmxDomain;

    /**
     * Kennzeichen, ob die Anwendung alleine betrieben wird (true) oder ob weitere ClusterKonten vorhanden
     * sind (false).
     */
    private boolean modusStandalone;

    /**
     * Liefert einen PolligCluster zur angegebenen Id.
     *
     * @param clusterId
     *            ID des zu ermittelnden Polling-Clusters.
     *
     * @return Polling-Cluster.
     */
    private PollingCluster getPollingCluster(String clusterId) {
        PollingCluster ergebnis = this.pollingClusterMap.get(clusterId);
        if (ergebnis == null) {
            throw new PollingClusterUnbekanntException(Fehlerschluessel.MSG_POLLING_CLUSTER_UBEKANNT,
                clusterId);
        }
        return ergebnis;
    }

    /**
     * Setzt das Feld 'konfiguration'.
     * @param konfiguration
     *            Neuer Wert für konfiguration
     */
    public void setKonfiguration(Konfiguration konfiguration) {
        this.konfiguration = konfiguration;
    }

    /**
     * Setzt das Feld 'jmxDomain'.
     * @param jmxDomain
     *            Neuer Wert für jmxDomain
     */
    public void setJmxDomain(String jmxDomain) {
        this.jmxDomain = jmxDomain;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean startePolling(String clusterId) {
        // Im Standalone-Modus muss niemand gefragt werden.
        if (this.modusStandalone) {
            return true;
        }

        // Ermitteln, ob ein anderer Knoten bereits ein Polling durchgeführt hat.
        PollingCluster pollingCluster = getPollingCluster(clusterId);
        JMXConnectionParameter[] jmxConnectionParameter = pollingCluster.getJmxConnectionParameter();
        boolean pollingAktiv = true;
        long zeitraum;

        for (JMXConnectionParameter verbindungsparameter : jmxConnectionParameter) {

            try {
                zeitraum = getZeitraumLetztePollingAktivitaet(verbindungsparameter, clusterId,
                    pollingCluster.getMBeanObjektName());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Zeitraum seit letzter Polling-Aktivitaet für Cluster-ID " + clusterId
                        + ", Knoten-ID " + verbindungsparameter.getId() + ", URL "
                        + verbindungsparameter.getJmxServiceUrl() + ": "
                        + (zeitraum == Long.MAX_VALUE ? "nie" : zeitraum + " ms"));
                }
            } catch (PollingUeberpruefungTechnicalException e) {
                LOG.error("PollingUeberpruefungTechnicalException", e);
                zeitraum = Long.MAX_VALUE;
            }
            if (zeitraum < (pollingCluster.getWartezeit() * 1000)) {
                pollingAktiv = false;
                break;
            }
        }

        if (pollingAktiv) {
            pollingCluster.aktualisiereZeitpunktLetztePollingAktivitaet();
        }
        return pollingAktiv;
    }

    /**
     * Liefert den Zeitraum in Millisekunden, der seit der letzten Ausführung des Pollings im System unter der
     * angegebenen URL vergangen ist.
     *
     * @param verbindungsparameter
     *            des zu prüfenden Systems.
     * @param clusterId
     *            Name des Polling-Clusters.
     * @param mBeanObjektName
     *            Name der MBean
     * @return Zeitraum in ms seit der letzten Ausführung des Pollings.
     */
    private long getZeitraumLetztePollingAktivitaet(JMXConnectionParameter verbindungsparameter,
        String clusterId, String mBeanObjektName) {

        long ergebnis = Long.MAX_VALUE;

        JMXConnector jmxc = null;
        try {

            jmxc = JMXConnectorFactory.connect(verbindungsparameter.getJmxServiceUrl(),
                verbindungsparameter.getEnvironment());
            MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();

            ObjectName mbeanName = new ObjectName(mBeanObjektName);
            ergebnis = (Long) mbsc.getAttribute(mbeanName, ZEITRAUM_LETZTE_POLLING_AKTIVITAET);

        } catch (MalformedObjectNameException e) {
            throw new PollingClusterKonfigurationException(Fehlerschluessel.MSG_MBEAN_OBJEKT_NAME_FEHLERHAFT,
                mBeanObjektName);
        } catch (NullPointerException e) {
            throw new PollingClusterKonfigurationException(Fehlerschluessel.MSG_MBEAN_OBJEKT_NAME_LEER);
        } catch (IOException e) {
            throw new PollingUeberpruefungTechnicalException(Fehlerschluessel.MSG_VERBINDUNGSFEHLER, e,
                verbindungsparameter.getIpAdressePort(), clusterId);
        } catch (AttributeNotFoundException e) {
            throw new PollingUeberpruefungTechnicalException(
                Fehlerschluessel.MSG_MBEAN_ATTRIBUT_NICHT_GEFUNDEN, e, ZEITRAUM_LETZTE_POLLING_AKTIVITAET,
                verbindungsparameter.getIpAdressePort());
        } catch (InstanceNotFoundException e) {
            throw new PollingUeberpruefungTechnicalException(
                Fehlerschluessel.MSG_MBEAN_INSTANZ_NICHT_GEFUNDEN, e, mBeanObjektName,
                verbindungsparameter.getIpAdressePort());
        } catch (MBeanException e) {
            throw new PollingUeberpruefungTechnicalException(Fehlerschluessel.MSG_MBEAN_ZUGRIFF_FEHLER, e,
                mBeanObjektName, verbindungsparameter.getIpAdressePort());
        } catch (ReflectionException e) {
            throw new PollingUeberpruefungTechnicalException(Fehlerschluessel.MSG_MBEAN_ZUGRIFF_FEHLER, e,
                mBeanObjektName, verbindungsparameter.getIpAdressePort());
        } finally {
            try {
                if (jmxc != null) {
                    jmxc.close();
                }
            } catch (IOException e) {
                LOG.error(EreignisSchluessel.JMX_VERBINDUNG_NICHT_GESCHLOSSEN,
                    "JMX-Verbindung konnte nicht geschlossen werden.", e);
            }
        }

        return ergebnis;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void aktualisiereZeitpunktLetztePollingAktivitaet(String clusterId) {
        // Im Standalone-Modus muss der Zeitpunkt nicht aktualisiert werden.
        if (this.modusStandalone) {
            return;
        }

        getPollingCluster(clusterId).aktualisiereZeitpunktLetztePollingAktivitaet();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getZeitpunktLetztePollingAktivitaet(String clusterId) {
        // Im Standalone-Modus kann kein Zeitpunkt ermittelt werden.
        if (this.modusStandalone) {
            return 0;
        }

        return getPollingCluster(clusterId).getZeitpunktLetztePollingAktivitaet();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        this.pollingClusterMap = lesePollingCluster();
    }

    /**
     * Liest die Konfiguration aus und baut eine Map mit Polling-Clustern auf.
     * @return Map mit den Polling-Clustern.
     */
    private Map<String, PollingCluster> lesePollingCluster() {
        Map<String, PollingCluster> pollingClusterMap = new HashMap<String, PollingCluster>(10);
        // JMX-Verbindungen lesen und Polling-Modus ermitteln.
        Map<String, JMXConnectionParameter> jmxVerbindungenMap = leseJmxVerbindungen();

        // Im Standalone-Modus wird keine Clusterkonfiguration benötigt.
        if (this.modusStandalone) {
            return pollingClusterMap;
        }

        String pollingClusterString =
            this.konfiguration.getAsString(KonfigurationSchluessel.CONF_POLLING_CLUSTER_LISTE);
        String[] clusterIds = pollingClusterString.split(",");
        PollingCluster pollingCluster;
        String jmxVerbindungenString;
        String[] jmxVerbindungen;
        String clusterName;
        JMXConnectionParameter jmxConnectionParameter;
        List<JMXConnectionParameter> jmxParameterListe;

        for (String clusterId : clusterIds) {
            clusterId = clusterId.trim();
            clusterName = this.konfiguration.getAsString(KonfigurationSchluessel.CONF_POLLING_CLUSTER_PRAEFIX
                + clusterId + KonfigurationSchluessel.CONF_POLLING_CLUSTER_NAME_SUFFIX);

            // zugeordnete JMX-Verbindungen ermitteln
            jmxVerbindungenString =
                this.konfiguration.getAsString(KonfigurationSchluessel.CONF_POLLING_CLUSTER_PRAEFIX
                    + clusterId + KonfigurationSchluessel.CONF_POLLING_CLUSTER_JMXVERBINDUNGEN_SUFFIX, "");
            jmxParameterListe = new ArrayList<JMXConnectionParameter>(5);
            if ("".equals(jmxVerbindungenString)) {
                // Wenn keine speziellen JMX-Verbindungen zugeordnet sind,
                // werden alle definierten JMX-Verbindungen zugeordnet
                for (JMXConnectionParameter jmxConnectionParam : jmxVerbindungenMap.values()) {
                    jmxParameterListe.add(jmxConnectionParam);
                }
            } else {
                jmxVerbindungen = jmxVerbindungenString.split(",");
                for (String jmxVerbindung : jmxVerbindungen) {
                    jmxVerbindung = jmxVerbindung.trim();
                    jmxConnectionParameter = jmxVerbindungenMap.get(jmxVerbindung);
                    if (jmxConnectionParameter == null) {
                        throw new PollingClusterKonfigurationException(
                            Fehlerschluessel.MSG_UNBEKANNTE_VERBINDUNGSZUORDNUNG, jmxVerbindung, clusterId);
                    }
                    jmxParameterListe.add(jmxConnectionParameter);
                }
            }

            // Polling-Cluster erzeugen
            pollingCluster = new PollingCluster(this.jmxDomain, clusterId, clusterName,
                this.konfiguration.getAsInteger(KonfigurationSchluessel.CONF_POLLING_CLUSTER_PRAEFIX
                    + clusterId + KonfigurationSchluessel.CONF_POLLING_CLUSTER_WARTEZEIT_SUFFIX),
                jmxParameterListe);
            pollingClusterMap.put(clusterId, pollingCluster);
        }

        return pollingClusterMap;
    }

    /**
     * Ermittelt die JMX-Verbindungsparameter aus der Konfiguration. Sind keine JMX-Verbindungen konfiguriert,
     * wird der Polling-Modus auf "Standalone" gesetzt und eine Warnung in die Log-Ausgabe geschrieben.
     *
     * @return Map mit den VerbindungsParametern.
     *
     * @throws PollingClusterKonfigurationException
     *             falls eine JMX-URL nicht korrekt gebildet wurde.
     */
    private Map<String, JMXConnectionParameter> leseJmxVerbindungen() {
        Map<String, JMXConnectionParameter> jmxConnectionMap =
            new HashMap<String, JMXConnectionParameter>(10);

        String jmxVerbindungsIdsString =
            this.konfiguration.getAsString(KonfigurationSchluessel.CONF_POLLING_JMXVERBINDUNGEN_LISTE, "");

        // Sind keine JMX-Verbindungen konfiguriert, wird der Polling-Modus auf "Standalone" gesetzt.
        if ("".equals(jmxVerbindungsIdsString)) {
            this.modusStandalone = true;
            LOG.warn(EreignisSchluessel.KEIN_JMX_VERBINDUNGS_PARAM,
                "Für das Polling der Anwendung wurden keine JMX-Verbindungsparameter angegeben! "
                    + "Der Polling-Modus wurde auf \"Standalone\" gesetzt!");
            return jmxConnectionMap;
        }

        this.modusStandalone = false;
        String[] jmxVerbindungsIds = jmxVerbindungsIdsString.split(",");
        JMXConnectionParameter jmxParameter;
        for (String jmxVerbindungId : jmxVerbindungsIds) {
            jmxVerbindungId = jmxVerbindungId.trim();
            jmxParameter = new JMXConnectionParameter(jmxVerbindungId,
                this.konfiguration.getAsString(KonfigurationSchluessel.CONF_POLLING_JMXVERBINDUNG_PRAEFIX
                    + jmxVerbindungId + KonfigurationSchluessel.CONF_POLLING_JMXVERBINDUNG_HOST_SUFFIX),
                this.konfiguration.getAsString(KonfigurationSchluessel.CONF_POLLING_JMXVERBINDUNG_PRAEFIX
                    + jmxVerbindungId + KonfigurationSchluessel.CONF_POLLING_JMXVERBINDUNG_PORT_SUFFIX),
                this.konfiguration.getAsString(KonfigurationSchluessel.CONF_POLLING_JMXVERBINDUNG_PRAEFIX
                    + jmxVerbindungId + KonfigurationSchluessel.CONF_POLLING_JMXVERBINDUNG_BENUTZER_SUFFIX,
                    null),
                this.konfiguration.getAsString(KonfigurationSchluessel.CONF_POLLING_JMXVERBINDUNG_PRAEFIX
                    + jmxVerbindungId + KonfigurationSchluessel.CONF_POLLING_JMXVERBINDUNG_PASSWORT_SUFFIX,
                    null));

            jmxConnectionMap.put(jmxVerbindungId, jmxParameter);
        }
        return jmxConnectionMap;
    }
}
