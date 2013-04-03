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
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2011
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

package org.dcm4chee.proxy.dimse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Properties;

import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;

import org.dcm4che.conf.api.ConfigurationException;
import org.dcm4che.data.Attributes;
import org.dcm4che.data.Tag;
import org.dcm4che.data.UID;
import org.dcm4che.data.VR;
import org.dcm4che.io.ContentHandlerAdapter;
import org.dcm4che.io.DicomInputStream;
import org.dcm4che.io.DicomOutputStream;
import org.dcm4che.io.SAXWriter;
import org.dcm4che.net.ApplicationEntity;
import org.dcm4che.net.Association;
import org.dcm4che.net.AssociationStateException;
import org.dcm4che.net.Commands;
import org.dcm4che.net.Dimse;
import org.dcm4che.net.DimseRSPHandler;
import org.dcm4che.net.Status;
import org.dcm4che.net.pdu.PresentationContext;
import org.dcm4che.net.service.DicomService;
import org.dcm4che.net.service.DicomServiceException;
import org.dcm4che.util.SafeClose;
import org.dcm4che.util.UIDUtils;
import org.dcm4chee.proxy.conf.ForwardRule;
import org.dcm4chee.proxy.conf.ProxyAEExtension;
import org.dcm4chee.proxy.conf.ProxyDeviceExtension;
import org.jboss.resteasy.util.Hex;

/**
 * @author Michael Backhaus <michael.backaus@agfa.com>
 */
public class Mpps extends DicomService {

    public Mpps() {
        super(UID.ModalityPerformedProcedureStepSOPClass);
    }

    @Override
    public void onDimseRQ(Association asAccepted, PresentationContext pc, Dimse dimse, Attributes cmd, Attributes data)
            throws IOException {
        switch (dimse) {
        case N_CREATE_RQ:
            onNCreateRQ(asAccepted, pc, dimse, cmd, data);
            break;
        case N_SET_RQ:
            onNSetRQ(asAccepted, pc, dimse, cmd, data);
            break;
        default:
            super.onDimseRQ(asAccepted, pc, dimse, cmd, data);
        }
    }

    private void onNCreateRQ(Association asAccepted, PresentationContext pc, Dimse dimse, Attributes cmd,
            Attributes data) throws IOException {
        Association asInvoked = (Association) asAccepted.getProperty(ProxyAEExtension.FORWARD_ASSOCIATION);
        if (asInvoked == null)
            try {
                processForwardRules(asAccepted, pc, dimse, cmd, data);
            } catch (ConfigurationException e) {
                LOG.error("{}: error processing {}: {}", new Object[] { asAccepted, dimse, e.getMessage() });
                LOG.debug(e.getMessage(), e);
                throw new DicomServiceException(Status.ProcessingFailure, e.getMessage());
            }
        else
            try {
                forwardNCreateRQ(asAccepted, asInvoked, pc, dimse, cmd, data);
            } catch (InterruptedException e) {
                LOG.error("{}: error processing {}: {}", new Object[] { asAccepted, dimse, e.getMessage() });
                LOG.debug(e.getMessage(), e);
                throw new DicomServiceException(Status.ProcessingFailure, e.getCause());
            }
    }

    private void processForwardRules(Association as, PresentationContext pc, Dimse dimse, Attributes cmd,
            Attributes data) throws ConfigurationException, IOException {
        ApplicationEntity ae = as.getApplicationEntity();
        ProxyAEExtension proxyAEE = ae.getAEExtension(ProxyAEExtension.class);
        List<ForwardRule> forwardRules = proxyAEE.filterForwardRulesOnDimseRQ(as, cmd, dimse);
        if (forwardRules.size() == 0)
            throw new ConfigurationException("no matching forward rule");

        Attributes rsp = (dimse == Dimse.N_CREATE_RQ) ? Commands.mkNCreateRSP(cmd, Status.Success) : Commands
                .mkNSetRSP(cmd, Status.Success);
        String iuid = rsp.getString(Tag.AffectedSOPInstanceUID);
        String cuid = rsp.getString(Tag.AffectedSOPClassUID);
        String tsuid = UID.ExplicitVRLittleEndian;
        Attributes fmi = Attributes.createFileMetaInformation(iuid, cuid, tsuid);
        for (ForwardRule rule : forwardRules) {
            List<String> destinationAETs = proxyAEE.getDestinationAETsFromForwardRule(as, rule, data);
            processDestinationAETs(as, dimse, data, proxyAEE, iuid, fmi, rule, destinationAETs);
        }
        try {
            as.writeDimseRSP(pc, rsp, data);
        } catch (AssociationStateException e) {
            Dimse dimseRSP = (dimse == Dimse.N_CREATE_RQ) ? Dimse.N_CREATE_RSP : Dimse.N_SET_RSP;
            LOG.warn("{} << {} failed: {}", new Object[] { as, dimseRSP.toString(), e.getMessage() });
            LOG.debug(e.getMessage(), e);
        }
    }

    private void processDestinationAETs(Association as, Dimse dimse, Attributes data, ProxyAEExtension pae,
            String iuid, Attributes fmi, ForwardRule rule, List<String> destinationAETs)
            throws TransformerFactoryConfigurationError, IOException {
        for (String calledAET : destinationAETs) {
            if (rule.getMpps2DoseSrTemplateURI() != null) {
                File dir = pae.getDoseSrPath();
                processMpps2DoseSRConversion(as, dimse, data, iuid, fmi, dir, calledAET, rule);
            } else {
                File dir = (dimse == Dimse.N_CREATE_RQ) ? pae.getNCreateDirectoryPath() : pae
                        .getNSetDirectoryPath();
                File file = createFile(as, dimse, data, iuid, fmi, dir, calledAET, rule);
                as.setProperty(ProxyAEExtension.FILE_SUFFIX, ".dcm");
                rename(as, file);
            }
        }
    }

    private void processMpps2DoseSRConversion(Association as, Dimse dimse, Attributes data, String iuid,
            Attributes fmi, File baseDir, String calledAET, ForwardRule rule)
            throws TransformerFactoryConfigurationError, IOException {
        if (dimse == Dimse.N_CREATE_RQ) {
            File file = createFile(as, dimse, data, iuid, fmi, baseDir, calledAET, rule);
            as.setProperty(ProxyAEExtension.FILE_SUFFIX, ".ncreate");
            rename(as, file);
        } else
            processNSetMpps2DoseSR(as, dimse, data, iuid, fmi, baseDir, calledAET, rule);
    }

    private void processNSetMpps2DoseSR(Association as, Dimse dimse, Attributes data, String iuid, Attributes fmi,
            File baseDir, String calledAET, ForwardRule rule) throws TransformerFactoryConfigurationError, IOException {
        ApplicationEntity ae = as.getApplicationEntity();
        ProxyAEExtension proxyAEE = ae.getAEExtension(ProxyAEExtension.class);
        File ncreateDir = new File(baseDir, calledAET);
        File ncreateFile = new File(ncreateDir, iuid + ".ncreate");
        Attributes ncreateAttrs = readAttributesFromNCreateFile(ncreateFile);
        data.merge(ncreateAttrs);
        Attributes doseSrData = new Attributes();
        String ppsSOPIUID = fmi.getString(Tag.MediaStorageSOPInstanceUID);
        transformMpps2DoseSr(as, proxyAEE, data, iuid, ppsSOPIUID, rule, doseSrData);
        String doseIuid = UIDUtils.createUID();
        String cuid = UID.XRayRadiationDoseSRStorage;
        String tsuid = UID.ImplicitVRLittleEndian;
        Attributes doseSrFmi = Attributes.createFileMetaInformation(doseIuid, cuid, tsuid);
        doseSrData.setString(Tag.SOPInstanceUID, VR.UI, doseIuid);
        doseSrData.setString(Tag.SeriesInstanceUID, VR.UI, UIDUtils.createUID());
        File doseSrFile = createFile(as, dimse, doseSrData, iuid, doseSrFmi, proxyAEE.getCStoreDirectoryPath(), calledAET, rule);
        LOG.info("{}: created Dose SR file {}", as, doseSrFile.getPath());
        as.setProperty(ProxyAEExtension.FILE_SUFFIX, ".dcm");
        rename(as, doseSrFile);
        deleteFile(as, ncreateFile);
    }

    private void deleteFile(Association as, File file) {
        if(file.delete())
            LOG.debug("{}: DELETE {}", as, file.getPath());
        else
            LOG.error("{}: failed to DELETE {}", as, file.getPath());
        File info = new File(file.getPath().substring(0, file.getPath().indexOf('.')) + ".info");
        if (info.delete())
            LOG.debug("{}: DELETE {}", as, info);
        else
            LOG.debug("{}: failed to DELETE {}", as, info);
        File path = new File(file.getParent());
        if (path.list().length == 0)
            path.delete();
    }

    private void transformMpps2DoseSr(Association as, ProxyAEExtension pae, Attributes data, String iuid,
            String ppsSOPIUID, ForwardRule rule, Attributes doseSrData)
            throws TransformerFactoryConfigurationError, DicomServiceException {
        try {
            Templates templates = pae.getApplicationEntity().getDevice().getDeviceExtension(ProxyDeviceExtension.class)
                    .getTemplates(rule.getMpps2DoseSrTemplateURI());
            SAXTransformerFactory factory = (SAXTransformerFactory) TransformerFactory.newInstance();
            TransformerHandler th = factory.newTransformerHandler(templates);
            Transformer tr = th.getTransformer();
            String irradiationEventUID = iuid.concat("1");
            tr.setParameter("IrradiationEventUID", irradiationEventUID);
            String hex = Hex.encodeHex(as.getCallingAET().getBytes());
            BigInteger bi = new BigInteger(hex, 16);
            tr.setParameter("DeviceObserverUID", bi);
            tr.setParameter("PerfomedProcedureStepSOPInstanceUID", ppsSOPIUID);
            th.setResult(new SAXResult(new ContentHandlerAdapter(doseSrData)));
            SAXWriter w = new SAXWriter(th);
            w.setIncludeKeyword(false);
            w.write(data);
        } catch (Exception e) {
            LOG.error(as + ": error converting MPPS to Dose SR: " + e.getMessage());
            LOG.debug(e.getMessage(), e);
            throw new DicomServiceException(Status.ProcessingFailure, e.getCause());
        }
    }

    private Attributes readAttributesFromNCreateFile(File ncreateFile)
            throws DicomServiceException {
        DicomInputStream in = null;
        try {
            in = new DicomInputStream(ncreateFile);
            return in.readDataset(-1, -1);
        } catch (IOException e) {
            LOG.error("Error reading file {}: {}", ncreateFile.getPath(), e.getMessage());
            LOG.debug(e.getMessage(), e);
            throw new DicomServiceException(Status.ProcessingFailure, e.getCause());
        } finally {
            SafeClose.close(in);
        }
    }

    protected File createFile(Association as, Dimse dimse, Attributes data, String iuid, Attributes fmi, File baseDir,
            String destinationAET, ForwardRule rule) throws IOException {
        File dir = new File(baseDir, destinationAET);
        dir.mkdir();
        File file = File.createTempFile("dcm", ".part", dir);
        DicomOutputStream out = null;
        Properties prop = new Properties();
        prop.setProperty("source-aet", as.getCallingAET());
        if (rule.getUseCallingAET() != null)
            prop.setProperty("use-calling-aet", rule.getUseCallingAET());
        String path = file.getPath();
        File info = new File(path.substring(0, path.length() - 5) + ".info");
        FileOutputStream infoOut = new FileOutputStream(info);
        try {
            LOG.info("{}: create {}", new Object[] { as, file });
            out = new DicomOutputStream(file);
            out.writeDataset(fmi, data);
            LOG.debug("{}: create {}", as, info.getPath());
            prop.store(infoOut, null);
        } catch (IOException e) {
            LOG.warn("{}: failed to create {} and/or {}", new Object[] { as, file.getPath(), info.getPath() });
            LOG.debug(e.getMessage(), e);
            file.delete();
            info.delete();
            throw new DicomServiceException(Status.OutOfResources, e.getCause());
        } finally {
            SafeClose.close(out);
            SafeClose.close(infoOut);
        }
        return file;
    }
    
    protected File rename(Association as, File file) throws DicomServiceException {
        String path = file.getPath();
        File dst = new File(path.substring(0, path.length() - 5).concat(
                (String) as.getProperty(ProxyAEExtension.FILE_SUFFIX)));
        if (file.renameTo(dst)) {
            dst.setLastModified(System.currentTimeMillis());
            LOG.info("{}: RENAME {} to {}", new Object[] { as, file, dst });
            return dst;
        } else {
            LOG.warn("{}: failed to RENAME {} to {}", new Object[] { as, file, dst });
            throw new DicomServiceException(Status.OutOfResources, "Failed to rename file");
        }
    }

    private void forwardNCreateRQ(final Association asAccepted, Association asInvoked, final PresentationContext pc,
            Dimse dimse, Attributes rq, Attributes data) throws IOException, InterruptedException {
        String tsuid = pc.getTransferSyntax();
        String cuid = rq.getString(Tag.AffectedSOPClassUID);
        String iuid = rq.getString(Tag.AffectedSOPInstanceUID);
        int msgId = rq.getInt(Tag.MessageID, 0);
        DimseRSPHandler rspHandler = new DimseRSPHandler(msgId) {

            @Override
            public void onDimseRSP(Association asInvoked, Attributes cmd, Attributes data) {
                super.onDimseRSP(asInvoked, cmd, data);
                try {
                    asAccepted.writeDimseRSP(pc, cmd, data);
                } catch (IOException e) {
                    LOG.error(asAccepted + ": error forwarding N-CREATE-RQ: " + e.getMessage());
                    LOG.debug(e.getMessage(), e);
                }
            }
        };
        asInvoked.ncreate(cuid, iuid, data, tsuid, rspHandler);
    }

    private void onNSetRQ(Association asAccepted, PresentationContext pc, Dimse dimse, Attributes cmd, Attributes data)
            throws IOException {
        Association asInvoked = (Association) asAccepted.getProperty(ProxyAEExtension.FORWARD_ASSOCIATION);
        if (asInvoked == null)
            try {
                processForwardRules(asAccepted, pc, dimse, cmd, data);
            } catch (ConfigurationException e) {
                LOG.error("{}: error processing {}: {}", new Object[] { asAccepted, dimse, e.getMessage() });
                LOG.debug(e.getMessage(), e);
            }
        else
            try {
                forwardNSetRQ(asAccepted, asInvoked, pc, dimse, cmd, data);
            } catch (InterruptedException e) {
                throw new DicomServiceException(Status.UnableToProcess, e.getMessage());
            }
    }

    private void forwardNSetRQ(final Association asAccepted, Association asInvoked, final PresentationContext pc,
            Dimse dimse, Attributes rq, Attributes data) throws IOException, InterruptedException {
        String tsuid = pc.getTransferSyntax();
        String cuid = rq.getString(Tag.RequestedSOPClassUID);
        String iuid = rq.getString(Tag.RequestedSOPInstanceUID);
        int msgId = rq.getInt(Tag.MessageID, 0);
        DimseRSPHandler rspHandler = new DimseRSPHandler(msgId) {

            @Override
            public void onDimseRSP(Association asInvoked, Attributes cmd, Attributes data) {
                super.onDimseRSP(asInvoked, cmd, data);
                try {
                    asAccepted.writeDimseRSP(pc, cmd, data);
                } catch (IOException e) {
                    LOG.error(asAccepted + ": error forwarding N-SET-RQ: ", e.getMessage());
                    LOG.debug(e.getMessage(), e);
                }
            }
        };
        asInvoked.nset(cuid, iuid, data, tsuid, rspHandler);
    }
}
