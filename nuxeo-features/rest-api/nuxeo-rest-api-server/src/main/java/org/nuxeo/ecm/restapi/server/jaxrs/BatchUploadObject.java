/*
 * (C) Copyright 2015 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 */
package org.nuxeo.ecm.restapi.server.jaxrs;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.Response.Status.Family;
import javax.ws.rs.core.Response.StatusType;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.map.ObjectMapper;
import org.nuxeo.ecm.automation.server.jaxrs.batch.BatchFileEntry;
import org.nuxeo.ecm.automation.server.jaxrs.batch.BatchManager;
import org.nuxeo.ecm.automation.server.jaxrs.batch.BatchResource;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.webengine.forms.FormData;
import org.nuxeo.ecm.webengine.model.WebObject;
import org.nuxeo.ecm.webengine.model.impl.AbstractResource;
import org.nuxeo.ecm.webengine.model.impl.ResourceTypeImpl;
import org.nuxeo.runtime.api.Framework;

/**
 * Batch upload endpoint.
 * <p>
 * Replaces the deprecated endpoints listed below:
 * <ul>
 * <li>POST /batch/upload, see {@link BatchResource#doPost(HttpServletRequest)}, use POST /upload/{batchId}/{fileIdx}
 * instead, see {@link #upload(HttpServletRequest, String, String)}</li>
 * <li>GET /batch/files/{batchId}, see {@link BatchResource#getFilesBatch(String)}, use GET /upload/{batchId} instead,
 * see {@link #getBatchInfo(String)} instead</li>
 * <li>GET /batch/drop/{batchId}, see {@link BatchResource#dropBatch(String)}, use DELETE /upload/{batchId} instead, see
 * {@link #dropBatch(String)}</li>
 * </ul>
 * Also provides new endpoints:
 * <ul>
 * <li>POST /upload, see {@link #initBatch()}</li>
 * <li>GET /upload/{batchId}/{fileIdx}, see {@link #getFileInfo(String, String)}</li>
 * </ul>
 * Largely inspired by the excellent Google Drive REST API documentation about <a
 * href="https://developers.google.com/drive/web/manage-uploads#resumable">resumable upload</a>.
 *
 * @since 7.4
 */
@WebObject(type = "upload")
public class BatchUploadObject extends AbstractResource<ResourceTypeImpl> {

    protected static final Log log = LogFactory.getLog(BatchUploadObject.class);

    protected static final String REQUEST_BATCH_ID = "batchId";

    protected static final String REQUEST_FILE_IDX = "fileIdx";

    public static final String UPLOAD_TYPE_NORMAL = "normal";

    public static final String UPLOAD_TYPE_CHUNKED = "chunked";

    @POST
    public Response initBatch() throws IOException {
        BatchManager bm = Framework.getService(BatchManager.class);
        String batchId = bm.initBatch();
        Map<String, String> result = new HashMap<String, String>();
        result.put("batchId", batchId);
        return buildResponse(Status.OK, result);
    }

    @POST
    @Path("{batchId}/{fileIdx}")
    public Response upload(@Context HttpServletRequest request, @PathParam(REQUEST_BATCH_ID) String batchId,
            @PathParam(REQUEST_FILE_IDX) String fileIdx) throws IOException {

        BatchManager bm = Framework.getService(BatchManager.class);
        if (!bm.hasBatch(batchId)) {
            return buildEmptyResponse(Status.NOT_FOUND);
        }

        boolean isMultipart = false;

        // Parameters are passed as request header, the request body is the stream
        String contentType = request.getHeader("Content-Type");
        String uploadType = request.getHeader("X-Upload-Type");
        String contentLength = request.getHeader("Content-Length");
        String uploadChunkIdx = request.getHeader("X-Upload-Chunk-Index");
        String chunkCount = request.getHeader("X-Upload-Chunk-Count");
        String fileName = request.getHeader("X-File-Name");
        String fileSize = request.getHeader("X-File-Size");
        String mimeType = request.getHeader("X-File-Type");
        InputStream is = null;

        String uploadedSize = contentLength;
        // Handle multipart case: mainly MSIE with jQueryFileupload
        if (contentType != null && contentType.contains("multipart")) {
            isMultipart = true;
            FormData formData = new FormData(request);
            Blob blob = formData.getFirstBlob();
            if (blob != null) {
                is = blob.getStream();
                fileName = blob.getFilename();
                mimeType = blob.getMimeType();
                uploadedSize = String.valueOf(blob.getLength());
            }
        } else {
            if (fileName != null) {
                fileName = URLDecoder.decode(fileName, "UTF-8");
            }
            is = request.getInputStream();
        }

        if (UPLOAD_TYPE_CHUNKED.equals(uploadType)) {
            log.debug(String.format("Uploading chunk [index=%s / total=%s] (%sb) for file %s", uploadChunkIdx,
                    chunkCount, uploadedSize, fileName));
            bm.addStream(batchId, fileIdx, is, Integer.valueOf(chunkCount), Integer.valueOf(uploadChunkIdx), fileName,
                    mimeType, Long.valueOf(fileSize));
        } else {
            // Use non chunked mode by default if X-Upload-Type header is not provided
            uploadType = UPLOAD_TYPE_NORMAL;
            log.debug(String.format("Uploading file %s (%sb)", fileName, uploadedSize));
            bm.addStream(batchId, fileIdx, is, fileName, mimeType);
        }

        StatusType status = Status.CREATED;
        Map<String, String> result = new HashMap<String, String>();
        result.put("batchId", batchId);
        result.put("fileIdx", fileIdx);
        result.put("uploadType", uploadType);
        result.put("uploadedSize", uploadedSize);
        if (UPLOAD_TYPE_CHUNKED.equals(uploadType)) {
            result.put("uploadedChunkId", uploadChunkIdx);
            result.put("chunkCount", chunkCount);
            BatchFileEntry fileEntry = bm.getFileEntry(batchId, fileIdx);
            if (!fileEntry.isChunksCompleted()) {
                status = new ResumeIncompleteStatusType();
            }
        }
        return buildResponse(status, result, isMultipart);
    }

    @GET
    @Path("{batchId}")
    public Response getBatchInfo(@PathParam(REQUEST_BATCH_ID) String batchId) throws IOException {
        BatchManager bm = Framework.getService(BatchManager.class);
        if (!bm.hasBatch(batchId)) {
            return buildEmptyResponse(Status.NOT_FOUND);
        }
        List<BatchFileEntry> fileEntries = bm.getFileEntries(batchId);
        if (CollectionUtils.isEmpty(fileEntries)) {
            return buildEmptyResponse(Status.NO_CONTENT);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (BatchFileEntry fileEntry : fileEntries) {
            result.add(getFileInfo(fileEntry));
        }
        return buildResponse(Status.OK, result);
    }

    @GET
    @Path("{batchId}/{fileIdx}")
    public Response getFileInfo(@PathParam(REQUEST_BATCH_ID) String batchId, @PathParam(REQUEST_FILE_IDX) String fileIdx)
            throws IOException {
        BatchManager bm = Framework.getService(BatchManager.class);
        if (!bm.hasBatch(batchId)) {
            return buildEmptyResponse(Status.NOT_FOUND);
        }
        BatchFileEntry fileEntry = bm.getFileEntry(batchId, fileIdx);
        if (fileEntry == null) {
            return buildEmptyResponse(Status.NOT_FOUND);
        }
        StatusType status = Status.OK;
        if (fileEntry.isChunked() && !fileEntry.isChunksCompleted()) {
            status = new ResumeIncompleteStatusType();
        }
        Map<String, Object> result = getFileInfo(fileEntry);
        return buildResponse(status, result);
    }

    @DELETE
    @Path("{batchId}")
    public Response dropBatch(@PathParam(REQUEST_BATCH_ID) String batchId) throws IOException {
        BatchManager bm = Framework.getLocalService(BatchManager.class);
        if (!bm.hasBatch(batchId)) {
            return buildEmptyResponse(Status.NOT_FOUND);
        }
        bm.clean(batchId);
        Map<String, String> result = new HashMap<String, String>();
        result.put("batchId", batchId);
        result.put("dropped", "true");
        return buildResponse(Status.OK, result);
    }

    protected Response buildResponse(StatusType status, Object object) throws IOException {
        return buildResponse(status, object, false);
    }

    protected Response buildResponse(StatusType status, Object object, boolean html) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        String result = mapper.writeValueAsString(object);
        if (html) {
            // For MSIE with iframe transport: we need to return HTML!
            return buildHTMLResponse(status, result);
        } else {
            return buildJSONResponse(status, result);
        }
    }

    protected Response buildJSONResponse(StatusType status, String message) {
        return Response.status(status).header("Content-Length", message.length()).type(MediaType.APPLICATION_JSON).entity(
                message).build();
    }

    protected Response buildHTMLResponse(StatusType status, String message) {
        message = "<html>" + message + "</html>";
        return Response.status(status).header("Content-Length", message.length()).type(MediaType.TEXT_HTML_TYPE).entity(
                message).build();
    }

    protected Response buildEmptyResponse(StatusType status) {
        return Response.status(status).build();
    }

    protected Map<String, Object> getFileInfo(BatchFileEntry fileEntry) throws UnsupportedEncodingException {
        Map<String, Object> info = new HashMap<>();
        boolean chunked = fileEntry.isChunked();
        String uploadType;
        if (chunked) {
            uploadType = UPLOAD_TYPE_CHUNKED;
        } else {
            uploadType = UPLOAD_TYPE_NORMAL;
        }
        info.put("name", fileEntry.getFileName());
        info.put("size", fileEntry.getFileSize());
        info.put("uploadType", uploadType);
        if (chunked) {
            info.put("uploadedChunkIds", fileEntry.getOrderedChunkIds());
            info.put("chunkCount", fileEntry.getChunkCount());
        }
        return info;
    }

    public final class ResumeIncompleteStatusType implements StatusType {

        @Override
        public int getStatusCode() {
            return 308;
        }

        @Override
        public String getReasonPhrase() {
            return "Resume Incomplete";
        }

        @Override
        public Family getFamily() {
            // Technically we don't use 308 Resume Incomplete as a redirection but it is the default family for 3xx
            // status codes defined by Response$Status
            return Family.REDIRECTION;
        }
    }

}