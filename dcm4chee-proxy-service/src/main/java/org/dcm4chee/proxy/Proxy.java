/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2012
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4chee.proxy;

import static org.dcm4che3.audit.AuditMessages.createEventIdentification;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.BindException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.dcm4che3.audit.AuditMessage;
import org.dcm4che3.audit.AuditMessages;
import org.dcm4che3.audit.AuditMessages.EventActionCode;
import org.dcm4che3.audit.AuditMessages.EventID;
import org.dcm4che3.audit.AuditMessages.EventOutcomeIndicator;
import org.dcm4che3.audit.AuditMessages.EventTypeCode;
import org.dcm4che3.audit.AuditMessages.RoleIDCode;
import org.dcm4che3.conf.api.ApplicationEntityCache;
import org.dcm4che3.conf.api.ConfigurationException;
import org.dcm4che3.conf.api.ConfigurationNotFoundException;
import org.dcm4che3.conf.api.DicomConfiguration;
import org.dcm4che3.conf.api.hl7.HL7ApplicationCache;
import org.dcm4che3.conf.api.hl7.HL7Configuration;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.DeviceService;
import org.dcm4che3.net.IncompatibleConnectionException;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.TransferCapability.Role;
import org.dcm4che3.net.audit.AuditLogger;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.dcm4chee.proxy.audit.AuditLog;
import org.dcm4chee.proxy.conf.ForwardRule;
import org.dcm4chee.proxy.conf.ProxyAEExtension;
import org.dcm4chee.proxy.conf.ProxyDeviceExtension;
import org.dcm4chee.proxy.dimse.CEcho;
import org.dcm4chee.proxy.dimse.CFind;
import org.dcm4chee.proxy.dimse.CGet;
import org.dcm4chee.proxy.dimse.CMove;
import org.dcm4chee.proxy.dimse.CStore;
import org.dcm4chee.proxy.dimse.Mpps;
import org.dcm4chee.proxy.dimse.StgCmt;
import org.dcm4chee.proxy.forward.Scheduler;
import org.dcm4chee.proxy.pix.PIXConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Michael Backhaus <michael.backhaus@agfa.com>
 * @author Hesham Elbadawi <bsdreko@gmail.com>
 */
public class Proxy extends DeviceService implements ProxyMBean {

    private static final Logger LOG = LoggerFactory.getLogger(Proxy.class);

    private static Proxy instance;

    private static double AUTO_CONFIG_PROGRESS=0;

    public static Proxy getInstance() {
        return Proxy.instance;
    }

    public static final String KS_TYPE = "org.dcm4chee.proxy.net.keyStoreType";
    public static final String KS_URL = "org.dcm4chee.proxy.net.keyStoreURL";
    public static final String KS_PASSWORD = "org.dcm4chee.proxy.net.storePassword";
    public static final String KEY_PASSWORD = "org.dcm4chee.proxy.net.keyPassword";

    private final DicomConfiguration dicomConfiguration;
    private PIXConsumer pixConsumer;
    private static Scheduler scheduler;
    private static ProxyCleanUpScheduler cleanUPScheduler;
    private final HL7ApplicationCache hl7AppCache;
    private ApplicationEntityCache aeCache;
    private final CEcho cecho;
    private final CStore cstore;
    private final StgCmt stgcmt;
    private final CFind cfind;
    private final CGet cget;
    private final CMove cmove;
    private final Mpps mpps;
    private final int restartTimeout = getRestartTimeout();

    public Proxy(DicomConfiguration dicomConfiguration,
            HL7Configuration hl7configuration, String deviceName)
            throws ConfigurationException {
        try {
            init(dicomConfiguration.findDevice(deviceName));
        } catch (ConfigurationNotFoundException e) {
            LOG.error("Could not find configuration for proxy device {}",
                    deviceName);
            throw new ConfigurationNotFoundException(e);
        } catch (ConfigurationException e) {
            LOG.error("Error loading configuration for proxy device {}",
                    deviceName);
            throw new ConfigurationException(e);
        }
        this.dicomConfiguration = dicomConfiguration;
        this.aeCache = new ApplicationEntityCache(dicomConfiguration);
        this.hl7AppCache = new HL7ApplicationCache(hl7configuration);
        this.pixConsumer = new PIXConsumer(hl7AppCache);
        this.cecho = new CEcho();
        this.cstore = new CStore(aeCache, "*");
        this.stgcmt = new StgCmt(aeCache);
        this.cfind = new CFind(aeCache, pixConsumer,
                "1.2.840.10008.5.1.4.1.2.1.1", "1.2.840.10008.5.1.4.1.2.2.1",
                "1.2.840.10008.5.1.4.1.2.3.1", "1.2.840.10008.5.1.4.31");
        this.cget = new CGet(aeCache, pixConsumer,
                "1.2.840.10008.5.1.4.1.2.1.3", "1.2.840.10008.5.1.4.1.2.2.3",
                "1.2.840.10008.5.1.4.1.2.3.3");
        this.cmove = new CMove(aeCache, pixConsumer,
                "1.2.840.10008.5.1.4.1.2.1.2", "1.2.840.10008.5.1.4.1.2.2.2",
                "1.2.840.10008.5.1.4.1.2.3.2");
        this.mpps = new Mpps(device.getDeviceExtension(AuditLogger.class));
        device.setDimseRQHandler(serviceRegistry());
        device.setAssociationHandler(new ProxyAssociationHandler(aeCache));
        setConfigurationStaleTimeout();
        Proxy.instance = this;
    }

    public PIXConsumer getPixConsumer() {
        return pixConsumer;
    }

    public void setPixConsumer(PIXConsumer pixConsumer) {
        this.pixConsumer = pixConsumer;
    }

    @Override
    public void start() throws Exception {

        if (isRunning())
            return;

        scheduler = new Scheduler(aeCache, device, new AuditLog(
                device.getDeviceExtension(AuditLogger.class)));
        cleanUPScheduler = new ProxyCleanUpScheduler(device);
        resetSpoolFiles("start-up");
        super.start();
        scheduler.start();
        cleanUPScheduler.start();
        log(AuditMessages.EventTypeCode.ApplicationStart);

    }

    @Override
    public void stop() {
        if (!isRunning())
            return;

        scheduler.stop();
        cleanUPScheduler.stop();
        super.stop();
        try {
            resetSpoolFiles("shut-down");
        } catch (IOException e) {
            LOG.error("Error reseting spool file: {}", e.getMessage());
            if (LOG.isDebugEnabled())
                e.printStackTrace();
        }
        log(EventTypeCode.ApplicationStop);
    }

    public void restart() throws Exception {
        stop();
        reload();
        int count = restartTimeout;
        while (!isRunning()) {
            try {
                start();
            } catch (BindException e) {
                if (count < 0) {
                    LOG.error("Error restarting {}: {}", instance.getDevice()
                            .getDeviceName(), e.getMessage());
                    return;
                }
                count--;
                Thread.sleep(1000);
            }
        }
    }

    public String setTransferCapabilities(String aeTitle) throws Exception {
        return instance.autoConfigTransferCapabilities(aeTitle);
    }

    public String getAutoConfigProgress()
    {
        return ""+instance.AUTO_CONFIG_PROGRESS;
    }
    public String getRegisteredAETs() throws Exception {
        String registeredAETitles[] = dicomConfiguration
                .listRegisteredAETitles();
        StringBuilder result = new StringBuilder();
        boolean separator = false;
        result.append("{\n\"registeredAETs\": [");
        for (String aet : registeredAETitles) {
            ApplicationEntity entity = aeCache.findApplicationEntity(aet);
            String description = "";
            if (entity != null && entity.getDescription() != null) {
                description = entity.getDescription();
            }
            result.append((separator ? "," : "") + "\n{\"aeTitle\": \"" + aet
                    + "\",");
            result.append("\"description\": \"" + description + "\"}");
            separator = true;
        }
        result.append("\n]\n}");
        return result.toString();
    }

    private static int getRestartTimeout() {
        String timeoutString = System
                .getProperty("org.dcm4chee.proxy.restart.timeout");
        try {
            return (timeoutString == null) ? 10 : Integer
                    .parseInt(timeoutString);
        } catch (NumberFormatException e) {
            LOG.error("{} ({})", new Object[] { e,
                    "org.dcm4chee.proxy.restart.timeout" });
            return 10;
        }
    }

    private void log(EventTypeCode eventType) {
        AuditLogger logger = device.getDeviceExtension(AuditLogger.class);
        if (logger != null && logger.isInstalled()) {
            Calendar timeStamp = logger.timeStamp();
            try {
                logger.write(
                        timeStamp,
                        createApplicationActivityMessage(logger, timeStamp,
                                eventType));
            } catch (Exception e) {
                LOG.error("Failed to write audit log message: "
                        + e.getMessage());
                if (LOG.isDebugEnabled())
                    e.printStackTrace();
            }
        }
    }

    private AuditMessage createApplicationActivityMessage(AuditLogger logger,
            Calendar timeStamp, EventTypeCode eventType) {
        AuditMessage msg = new AuditMessage();
        msg.setEventIdentification(createEventIdentification(
                EventID.ApplicationActivity, EventActionCode.Execute,
                timeStamp, EventOutcomeIndicator.Success, null, eventType));
        msg.getAuditSourceIdentification().add(
                logger.createAuditSourceIdentification());
        msg.getActiveParticipant().add(
                logger.createActiveParticipant(true, RoleIDCode.Application));
        return msg;
    }

    protected DicomServiceRegistry serviceRegistry() {
        DicomServiceRegistry dcmService = new DicomServiceRegistry();
        dcmService.addDicomService(cecho);
        dcmService.addDicomService(cstore);
        dcmService.addDicomService(stgcmt);
        dcmService.addDicomService(cfind);
        dcmService.addDicomService(cget);
        dcmService.addDicomService(cmove);
        dcmService.addDicomService(mpps);
        return dcmService;
    }

    @Override
    public void reload() throws Exception {
        scheduler.stop();
        cleanUPScheduler.stop();
        device.getDeviceExtension(ProxyDeviceExtension.class)
                .clearTemplatesCache();
        // Make sure the configuration is re-loaded from the backend
        dicomConfiguration.sync();
        device.reconfigure(dicomConfiguration.findDevice(device.getDeviceName()));
        this.aeCache = new ApplicationEntityCache(dicomConfiguration);
        setConfigurationStaleTimeout();
        if (isRunning()) {
            device.rebindConnections();
            scheduler.start();
            cleanUPScheduler.start();
        }
    }

    private void setConfigurationStaleTimeout() {
        int staleTimeout = device
                .getDeviceExtension(ProxyDeviceExtension.class)
                .getConfigurationStaleTimeout();
        aeCache.setStaleTimeout(staleTimeout);
        hl7AppCache.setStaleTimeout(staleTimeout);
    }

    private void resetSpoolFiles(String action) throws IOException {
        Collection<ApplicationEntity> proxyAEs = instance.getDevice()
                .getApplicationEntities();
        for (ApplicationEntity ae : proxyAEs) {
            ProxyAEExtension proxyAEE = ae
                    .getAEExtension(ProxyAEExtension.class);
            if (proxyAEE != null) {
                LOG.info("Reset spool files for {} on {}", ae.getAETitle(),
                        action);
                // clear cstore spool dir
                renameSndFiles(proxyAEE.getCStoreDirectoryPath(), action);
                deletePartFiles(proxyAEE.getCStoreDirectoryPath(), action);
                deleteTmpBulkFiles(proxyAEE.getCStoreDirectoryPath(), action);
                deleteIncompleteDcmFiles(proxyAEE.getCStoreDirectoryPath(),
                        action);
                // clear naction spool dir
                for (File path : proxyAEE.getNactionDirectoryPath().listFiles()) {
                    renameSndFiles(path, action);
                    deletePartFiles(path, action);
                }
                // clear nevent spool dir
                for (File path : proxyAEE.getNeventDirectoryPath().listFiles()) {
                    renameSndFiles(path, action);
                    deletePartFiles(path, action);
                }
                // clear ncreate spool dir
                renameSndFiles(proxyAEE.getNCreateDirectoryPath(), action);
                deletePartFiles(proxyAEE.getNCreateDirectoryPath(), action);
                // clear nset spool dir
                renameSndFiles(proxyAEE.getNSetDirectoryPath(), action);
                deletePartFiles(proxyAEE.getNSetDirectoryPath(), action);
            }
        }
    }

    private void deleteIncompleteDcmFiles(File path, String action) {
        String[] dirs = path.list(dirFilter());
        for (String dir : dirs)
            deleteIncompleteDcmFiles(new File(path, dir), action);

        String[] dcmFiles = path.list(dcmFileFilter());
        ArrayList<File> infoFiles = listFiles(infoFileFilter(), path);
        for (String dcmFile : dcmFiles) {
            File file = new File(path, dcmFile);
            File infoFile = new File(file.getPath()
                    .substring(0, file.getPath().length() - 3).concat("info"));
            if (infoFiles.contains(infoFile))
                continue;

            if (file.delete())
                LOG.info(
                        "Delete incomplete dcm file {} (without info file) on {}",
                        file.getPath(), action);
            else
                LOG.info(
                        "Failed to delete incomplete dcm file {} (without info file) on {}",
                        file.getPath(), action);
        }
    }

    public ArrayList<File> listFiles(FilenameFilter filter, File dir) {
        String ss[] = dir.list();
        if (ss == null)
            return null;
        ArrayList<File> files = new ArrayList<>();
        for (String s : ss)
            if ((filter == null) || filter.accept(dir, s))
                files.add(new File(dir, s));
        return files;
    }

    private void renameSndFiles(File path, String action) {
        for (String aet : path.list(dirFilter())) {
            File dir = new File(path, aet);
            File[] sndFiles = dir.listFiles(sndFileFilter());
            for (File sndFile : sndFiles) {
                File parent = sndFile.getParentFile();
                String sndFileName = sndFile.getName();
                if (sndFileName.lastIndexOf('.') != -1) {
                    File dst = new File(parent, sndFileName.substring(0,
                            sndFileName.length() - 4));
                    if (sndFile.renameTo(dst))
                        LOG.info("Rename {} to {} on {}", new Object[] {
                                sndFile.getPath(), dst.getPath(), action });
                    else
                        LOG.info("Failed to rename {} to {} on {}",
                                new Object[] { sndFile.getPath(),
                                        dst.getPath(), action });
                } else {
                    // delete snd files
                    if (sndFile.delete())
                        LOG.info("Delete {} on {}",
                                new Object[] { sndFile.getPath(), action });
                    else
                        LOG.info("Failed to delete {}  on {}", new Object[] {
                                sndFile.getPath(), action });
                }

            }
        }
    }

    public static FilenameFilter sndFileFilter() {
        return new FilenameFilter() {

            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".snd");
            }
        };
    }

    private FilenameFilter dcmFileFilter() {
        return new FilenameFilter() {

            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".dcm");
            }
        };
    }

    private FilenameFilter infoFileFilter() {
        return new FilenameFilter() {

            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".info");
            }
        };
    }

    private void deletePartFiles(File path, String action) {
        for (String partFileName : path.list(partFileFilter())) {
            File partFile = new File(path, partFileName);
            if (partFile.delete())
                LOG.info("Delete {} on {}", partFile.getPath(), action);
            else
                LOG.info("Failed to delete {} on {}", partFile.getPath(),
                        action);
        }
    }
    private void deleteTmpBulkFiles(File path, String action) {
        for (String tmpBulkFileName : path.list(tmpBulkFileFilter())) {
            File tmpBulkFile = new File(path, tmpBulkFileName);
            if (tmpBulkFile.delete())
                LOG.info("Delete {} on {}", tmpBulkFile.getPath(), action);
            else
                LOG.info("Failed to delete {} on {}", tmpBulkFile.getPath(),
                        action);
        }
    }
    private FilenameFilter partFileFilter() {
        return new FilenameFilter() {

            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".part");
            }
        };
    }

    private FilenameFilter tmpBulkFileFilter() {
        return new FilenameFilter() {

            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".tmpBulkData");
            }
        };
    }
    
    private FilenameFilter dirFilter() {
        return new FilenameFilter() {

            @Override
            public boolean accept(File dir, String name) {
                return new File(dir, name).isDirectory();
            }
        };
    }

    public ApplicationEntity findApplicationEntity(String aet)
            throws ConfigurationException {
        return aeCache.findApplicationEntity(aet);
    }

    public String autoConfigTransferCapabilities(final String proxyAETitle) {
        AUTO_CONFIG_PROGRESS=0;
        try {
            ProxyDeviceExtension proxyAEE = this.device
                    .getDeviceExtensionNotNull(ProxyDeviceExtension.class);

            try {
                final ApplicationEntity prxAE = findApplicationEntity(proxyAETitle);
                
                try {
                    dicomConfiguration.sync();
                } catch (ConfigurationException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                
                proxyAEE.getFileForwardingExecutor().execute(new Runnable() {

                    @Override
                    public void run() {

                        ProxyAEExtension prxExt = prxAE
                                .getAEExtensionNotNull(ProxyAEExtension.class);

                        double expectedProgressMax = 0;
                        for(ForwardRule rule : prxExt.getForwardRules())
                            for(String dest: rule.getDestinationAETitles())
                                expectedProgressMax++;
                        
                        double progressIncrement=100;
                        if(expectedProgressMax>0)
                        progressIncrement = 100/expectedProgressMax;
                        else
                            AUTO_CONFIG_PROGRESS+=progressIncrement;
                        for (ForwardRule rule : prxExt.getForwardRules())
                            for (String destinationAET : rule
                                    .getDestinationAETitles()) {
                                Connection tmpConn = null;
                                try {
                                    for (Connection tmp : aeCache
                                            .findApplicationEntity(
                                                    destinationAET)
                                            .getConnections()) {
                                        if (tmp.isInstalled() && tmp.isServer()
                                                && !tmp.isTls()) {
                                            tmpConn = tmp;
                                            break;
                                        }
                                    }
                                } catch (ConfigurationException e1) {
                                    LOG.error(
                                            "Error retrieving server connection for AE , {} during autoconfig, {}",
                                            destinationAET, e1);
                                }

                                try {

                                    ApplicationEntity sourceAE = prxAE;
                                    ArrayList<TransferCapability> tcs = (ArrayList<TransferCapability>) sourceAE
                                            .getTransferCapabilities();
                                    ArrayList<PresentationContext> pcs = addChunkedPCsandSend(
                                            prxAE, new AAssociateRQ(), tcs,
                                            tmpConn, destinationAET);
                                    // add accepted ones
                                    ArrayList<PresentationContext> acceptedPCs = new ArrayList<PresentationContext>();
                                    for (PresentationContext pc : pcs)
                                        if (pc.isAccepted())
                                            acceptedPCs.add(pc);

                                    ApplicationEntity destinationAE = aeCache
                                            .findApplicationEntity(destinationAET);

                                    TransferCapability[] finalTCs = mergeTCs(acceptedPCs);

                                    for (TransferCapability tc : finalTCs) {
                                        tc.setCommonName(tc.getSopClass());
                                        destinationAE.addTransferCapability(tc);
                                    }
                                    if (finalTCs.length != 0)
                                        LOG.debug(
                                                "Added acceptable transfer capabilities for AE {},"
                                                        + " Used the AE {} as source for transfer capabilities",
                                                destinationAET,
                                                sourceAE.getAETitle());
                                    dicomConfiguration.merge(destinationAE
                                            .getDevice());

                                } catch (ConfigurationException e) {
                                    LOG.error(
                                            "Configuration backend error - {}",
                                            e);

                                }
                                AUTO_CONFIG_PROGRESS+=progressIncrement;
                            }
                        try {
                            dicomConfiguration.sync();
                        } catch (ConfigurationException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    }
                });
            } catch (ConfigurationException e1) {
                LOG.error(
                        "Unable to find the proxyAETitle provided by the setTransferCapabilities web request, {}",
                        e1);
                return "unable to start autoconfiguration- exception: \n" + e1;
            }
        } catch (Exception e) {
            return "unable to start autoconfiguration- exception: \n" + e;
        }
        return "successfully started autoconfiguration";
    }

    private ArrayList<PresentationContext> addChunkedPCsandSend(
            ApplicationEntity ae, AAssociateRQ rq,
            ArrayList<TransferCapability> tcs, Connection destination,
            String probedAET) {
        int pcID = 1;
        ArrayList<ArrayList<PresentationContext>> lst = new ArrayList<ArrayList<PresentationContext>>();
        ArrayList<PresentationContext> fullListSingleTS = new ArrayList<PresentationContext>();
        ArrayList<PresentationContext> allACPCs = new ArrayList<PresentationContext>();

        for (TransferCapability tc : tcs)
            for (String ts : tc.getTransferSyntaxes()) {
                fullListSingleTS.add(new PresentationContext(pcID, tc
                        .getSopClass(), ts));
                pcID++;
                if (fullListSingleTS.size() > 127) {
                    lst.add(fullListSingleTS);
                    pcID = 1;
                    fullListSingleTS = new ArrayList<PresentationContext>();
                }
            }

        rq.setCallingAET(ae.getAETitle());
        rq.setCalledAET(probedAET);
        // now start sending 128 each
        for (ArrayList<PresentationContext> subList : lst) {
            rq = new AAssociateRQ();
            rq.setCallingAET(ae.getAETitle());
            rq.setCalledAET(probedAET);
            for (PresentationContext pc : subList)
                rq.addPresentationContext(pc);
            try {

                Association as = openAssociation(ae, rq, destination);
                // cache the pcs
                for (PresentationContext pcAC : as.getAAssociateAC()
                        .getPresentationContexts()) {
                    if (pcAC.isAccepted())
                        allACPCs.add(rq.getPresentationContext(pcAC.getPCID()));
                }

                as.release();
            } catch (Exception e) {
                e.printStackTrace();
                LOG.info(
                        "Unable to connect to AE, {}, Will not set the AE transfer capabilities in the configuration",
                        e);
                break;
            }
        }

        return allACPCs;
    }

    public Association openAssociation(ApplicationEntity ae, AAssociateRQ rq,
            Connection remote) throws IOException, InterruptedException,
            IncompatibleConnectionException, GeneralSecurityException {
        if (ae.getDevice().getExecutor() == null) {
            ExecutorService executorService = Executors
                    .newSingleThreadExecutor();
            ScheduledExecutorService scheduledExecutorService = Executors
                    .newSingleThreadScheduledExecutor();
            ae.getDevice().setExecutor(executorService);
            ae.getDevice().setScheduledExecutor(scheduledExecutorService);
        }
        Association as = ae.connect(remote, rq);
        return as;
    }

    private TransferCapability[] mergeTCs(
            ArrayList<PresentationContext> acceptedPCs) {
        ArrayList<TransferCapability> tmpTCs = new ArrayList<TransferCapability>();
        for (PresentationContext pc : acceptedPCs) {
            String abstractSyntax = pc.getAbstractSyntax();
            if (containsAbstractSyntax(tmpTCs, abstractSyntax)) {
                continue;
            }
            TransferCapability tmpTC = new TransferCapability();
            tmpTC.setRole(Role.SCP);
            ArrayList<String> tmpTS = new ArrayList<String>();
            tmpTC.setSopClass(abstractSyntax);
            for (PresentationContext tmp : acceptedPCs) {

                if (tmp.getAbstractSyntax().compareToIgnoreCase(abstractSyntax) == 0) {
                    if (!tmpTS.contains(tmp.getTransferSyntax())) {
                        tmpTS.add(tmp.getTransferSyntax());
                    }
                }
            }
            String[] tmpTSStr = new String[tmpTS.size()];
            tmpTS.toArray(tmpTSStr);
            tmpTC.setTransferSyntaxes(tmpTSStr);
            tmpTCs.add(tmpTC);

        }
        TransferCapability[] TCs = new TransferCapability[tmpTCs.size()];
        tmpTCs.toArray(TCs);
        return TCs;
    }

    private boolean containsAbstractSyntax(
            ArrayList<TransferCapability> tmpTCs, String abstractSyntax) {
        for (TransferCapability tc : tmpTCs) {
            if (tc.getSopClass().compareToIgnoreCase(abstractSyntax) == 0) {
                return true;
            }
        }
        return false;
    }

}
