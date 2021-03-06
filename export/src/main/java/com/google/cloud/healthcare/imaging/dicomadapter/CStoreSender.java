// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.cloud.healthcare.imaging.dicomadapter;

import com.google.cloud.healthcare.DicomWebClient;
import com.google.cloud.healthcare.imaging.dicomadapter.monitoring.Event;
import com.google.cloud.healthcare.imaging.dicomadapter.monitoring.MonitoringService;
import com.google.common.io.CountingInputStream;
import com.google.pubsub.v1.PubsubMessage;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.dcm4che3.data.Tag;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.util.TagUtils;
import org.json.JSONArray;

// CStoreSender sends DICOM to peer using DIMSE C-STORE protocol.
public class CStoreSender implements DicomSender {
  private static final String SOP_CLASS_UID_TAG = TagUtils.toHexString(Tag.SOPClassUID);
  private static final String SOP_INSTANCE_UID_TAG = TagUtils.toHexString(Tag.SOPInstanceUID);
  private final ApplicationEntity applicationEntity;
  private final String dimsePeerAET;
  private final String dimsePeerIP;
  private final int dimsePeerPort;
  private final DicomWebClient dicomWebClient;

  CStoreSender(
      ApplicationEntity applicationEntity,
      String dimsePeerAET,
      String dimsePeerIP,
      int dimsePeerPort,
      DicomWebClient dicomWebClient) {
    this.applicationEntity = applicationEntity;
    this.dimsePeerAET = dimsePeerAET;
    this.dimsePeerIP = dimsePeerIP;
    this.dimsePeerPort = dimsePeerPort;
    this.dicomWebClient = dicomWebClient;
  }

  @Override
  public void send(PubsubMessage message) throws Exception {
    String wadoUri = message.getData().toStringUtf8();
    String qidoUri = qidoFromWadoUri(wadoUri);

    // Invoke QIDO-RS to get DICOM tags needed to invoke C-Store.
    JSONArray qidoResponse = dicomWebClient.qidoRs(qidoUri);
    if (qidoResponse.length() != 1) {
      throw new IllegalArgumentException(
          "Invalid QidoRS JSON array length for response: " + qidoResponse.toString());
    }
    String sopClassUid = AttributesUtil.getTagValue(qidoResponse.getJSONObject(0),
        SOP_CLASS_UID_TAG);
    String sopInstanceUid = AttributesUtil.getTagValue(qidoResponse.getJSONObject(0),
        SOP_INSTANCE_UID_TAG);

    // Invoke WADO-RS to get bulk DICOM.
    InputStream responseStream = dicomWebClient.wadoRs(wadoUri);

    CountingInputStream countingStream = new CountingInputStream(responseStream);
    DicomClient.connectAndCstore(sopClassUid, sopInstanceUid, countingStream,
        applicationEntity, dimsePeerAET, dimsePeerIP, dimsePeerPort);
    MonitoringService.addEvent(Event.BYTES, countingStream.getCount());
  }

  // Derives a QIDO-RS path using a WADO-RS path.
  // TODO(b/72555677): Find an easier way to do this instead of string manipulation.
  private String qidoFromWadoUri(String wadoUri) {
    Path wadoPath = Paths.get(wadoUri);
    String instanceUid = wadoPath.getFileName().toString();
    Path wadoParentPath = wadoPath.getParent();
    String qidoUri =
        String.format(
            "%s?%s=%s",
            wadoParentPath.toString(), SOP_INSTANCE_UID_TAG, instanceUid);
    return qidoUri;
  }
}
