import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.swing.text.DefaultEditorKit.CopyAction;

import jdk.jfr.internal.LogLevel;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import sun.rmi.runtime.Log;

public class DelegsVideoSupplierServlet extends HttpServlet {

    private static final int MAX_VIDEO_DURATION = 5;
    private static final int DEFAULT_BUFFER = 4096;
    private static final long DEFAULT_EXPIRE_TIME = 604800000L;
    private static final long serialVersionUID = 8014170545544095462L;
    private static final String PROPERTY_FILE_PATH = "WEB-INF/resource/VideoSupplierServletConfig.properties";

    private Properties properties;
    private ServletFileUpload uploader = null;
    private DelegsVideoSupplierService videoSupplierService;
    Logger LOG = Logger.getLogger("DelegsVideoSupplierServlet.class");

    @Override
    public void init() {
        try (InputStream input = getServletContext().getResourceAsStream(PROPERTY_FILE_PATH)) {
            properties = new Properties();
            properties.load(input);
            String videoDirPath = properties.getProperty("video.upload.dir");

            if (videoDirPath == null || videoDirPath.isEmpty())
                throw new NullPointerException();

            videoSupplierService = new DelegsVideoSupplierService(videoDirPath);

        } catch (IOException | NullPointerException ex) {
            ex.printStackTrace();
            super.destroy();
        }

        // create necessary objects for file handling
        DiskFileItemFactory fileFactory = new DiskFileItemFactory();
        fileFactory.setRepository(videoSupplierService.getVideoPath());
        uploader = new ServletFileUpload(fileFactory);
    }


    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        processRequest(req, resp, false);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        processRequest(req, resp, true);
    }

    private void processRequest(HttpServletRequest request, HttpServletResponse response, boolean content) throws IOException {
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS, PUT, DELETE, HEAD");
        response.setHeader("Access-Control-Allow-Headers", "X-PINGOTHER, Origin, X-Requested-With, Content-Type, Accept");
        response.setHeader("Access-Control-Max-Age", "1728000");

        String fileName = request.getPathInfo().substring(1);

        if (fileName.isEmpty()) {
            response.getWriter().append("media not found");
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        File file = new File(videoSupplierService.getVideoPath() + File.separator + fileName);

        if (!file.exists()) {
            response.getWriter().append("media not found");
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        long length = file.length();
        long lastMod = file.lastModified();
        String eTag = fileName + "_" + length + "_" + lastMod;
        long expires = System.currentTimeMillis() + DEFAULT_EXPIRE_TIME;

        String ifNoneMatch = request.getHeader("If-None-Match");
        if (ifNoneMatch != null && matches(ifNoneMatch, eTag)) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            response.setHeader("ETag", eTag);
            response.setDateHeader("Expires", expires);
            return;
        }

        long ifModifiedSince = request.getDateHeader("If-Modified-Since");
        if (ifNoneMatch == null && ifModifiedSince != -1 && ifModifiedSince + 1000 > lastMod) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            response.setHeader("ETag", eTag);
            response.setDateHeader("Expires", expires);
            return;
        }


        String ifMatch = request.getHeader("If-Match");
        if (ifMatch != null && !matches(ifMatch, eTag)) {
            response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
            return;
        }

        long ifUnmodifiedSince = request.getDateHeader("If-Unmodified-Since");
        if (ifUnmodifiedSince != -1 && ifUnmodifiedSince + 1000 <= lastMod) {
            response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
            return;
        }

        Range full = new Range(0, length - 1, length);
        List<Range> ranges = new ArrayList<>();
        String range = request.getHeader("Range");
        if (range != null) {
            if (!range.matches("^bytes=\\d*-\\d*(,\\d*-\\d*)*$")) {
                response.setHeader("Content-Range", "bytes */" + length);
                response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                return;
            }

            String ifRange = request.getHeader("If-Range");
            if (ifRange != null && !ifRange.equals(eTag)) {
                try {
                    long ifRangeTime = request.getDateHeader("If-Range");
                    if (ifRangeTime != -1 && ifRangeTime + 1000 < lastMod) {
                        ranges.add(full);
                    }
                } catch (IllegalArgumentException ignore) {
                    ranges.add(full);
                }
            }

            if (ranges.isEmpty()) {
                for (String part : range.substring(6).split(",")) {
                    long start = Long.parseLong(part.substring(0, part.indexOf("-")));
                    long end = 0;
                    try {
                        end = Long.parseLong(part.substring(part.indexOf("-") + 1, part.length()));
                    } catch (Exception e) {
                        end = length - 1;
                    }


                    if (start == -1) {
                        start = length - end;
                        end = length - 1;
                    } else if (end == -1 || end > length - 1) {
                        end = length - 1;
                    }

                    if (start > end) {
                        response.setHeader("Content-Range", "bytes */" + length);
                        response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                        return;
                    }

                    ranges.add(new Range(start, end, length));
                }
            }
        }

        String contentType = getServletContext().getMimeType(fileName);
        String disposition = "attachment";

        if (contentType == null)
            contentType = "application/octet-stream";

        response.setBufferSize(DEFAULT_BUFFER);
        response.setHeader("Content-Disposition", disposition + ";filename=\"" + fileName + "\"");
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("ETag", eTag);
        response.setDateHeader("Last-Modified", lastMod);
        response.setDateHeader("Expires", expires);
        RandomAccessFile input = null;
        OutputStream output = null;

        try {
            input = new RandomAccessFile(file, "r");
            output = response.getOutputStream();

            if (ranges.isEmpty() || ranges.get(0) == full) {
                Range fullRange = full;
                response.setContentType(contentType);

                if (content) {
                    response.setContentLength((int) fullRange.length);
                    copy(input, output, fullRange.start, fullRange.length);
                }
            } else if (ranges.size() == 1) {
                Range singleRange = ranges.get(0);
                response.setContentType(contentType);
                response.setHeader("Content-Range", "bytes " + singleRange.start + "-" + singleRange.end + "/" + singleRange.total);
                response.setContentLength((int) singleRange.length);
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);

                if (content) {
                    copy(input, output, singleRange.start, singleRange.length);
                }
            } else {
                response.setContentType("multipart/byteranges; boundary=MULTIPART_BYTERANGES");
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);

                if (content) {
                    ServletOutputStream sos = (ServletOutputStream) output;

                    for (Range partialRange : ranges) {
                        sos.println();
                        sos.println("--MULTIPART_BYTERANGES");
                        sos.println("Content-Type: " + contentType);
                        sos.println("Content-Range: bytes " + partialRange.start + "-" + partialRange.end + "/" + partialRange.total);

                        copy(input, output, partialRange.start, partialRange.length);
                    }
                    sos.println();
                    sos.println("--MULTIPART_BYTERANGES--");
                }
            }
        } catch (Exception e) {
            LOG.warning(e.getMessage());
            for (StackTraceElement exceptmessage : e.getStackTrace()) {
                LOG.warning(exceptmessage.toString());
            }
        } finally {
            if (input != null) {
                input.close();
            }
            if (output != null) {
                output.close();
            }
        }
    }

    private void copy(RandomAccessFile input, OutputStream output, long start, long length) throws IOException {
        byte[] buffer = new byte[DEFAULT_BUFFER];
        int read;

        if (input.length() == length) {
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        } else {
            input.seek(start);
            long toRead = length;
            while ((read = input.read(buffer)) > 0) {
                if ((toRead -= read) > 0) {
                    output.write(buffer, 0, read);
                } else {
                    output.write(buffer, 0, (int) toRead + read);
                    break;
                }
            }
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (!ServletFileUpload.isMultipartContent(req)) {
            throw new ServletException("Content type is not multipart/form-data");
        }

        List<FileItem> fileItemsList;

        try {
            fileItemsList = uploader.parseRequest(req);
        } catch (FileUploadException e) {
            LOG.warning(e.getMessage());
            e.printStackTrace();
            throw new IOException(e);
        }
        for (FileItem fileItem : fileItemsList) {
            String fileHash = DigestUtils.sha256Hex(fileItem.getFieldName() + LocalDateTime.now().toString());

            String fileName = generateFileNameWithHash(fileItem, fileHash);

            try {
                convertVideo(fileItem, fileName);

                String url = req.getRequestURL().toString();
                if (url.charAt(url.length() - 1) != '/') {
                    url += File.separator;
                }
                url += fileName;

                resp.setCharacterEncoding("UTF-8");
                resp.getWriter().append(url);
                resp.setStatus(HttpServletResponse.SC_OK);
            } catch (Exception e) {
                LOG.warning(e.getMessage());
                e.printStackTrace();
                resp.getWriter().append(e.getMessage());
                resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            }
        }
    }

    private void convertVideo(FileItem fileItem, String fileName) throws Exception {
        String dstFilePath = videoSupplierService.getVideoPath() + File.separator + "Dst_" + fileName;
        String finalFilePath = videoSupplierService.getVideoPath() + File.separator + fileName;
        File srcFile = File.createTempFile(finalFilePath, "_src");
        String ffmpegPath = properties.getProperty("video.ffmpeg.dir");

        fileItem.write(srcFile);

        checkVideoLength(fileItem, srcFile);

        // convert video
        Thread videoConvertingThread = new Thread(() -> {
            try {
                ProcessBuilder videoConversion = new ProcessBuilder( //
                        ffmpegPath + "/ffmpeg",//
                        "-y", //
                        "-i", srcFile.getAbsolutePath(), // input
                        "-vcodec", "copy", //
                        "-an", // remove audio
                        "-movflags", "faststart", // move moov section to beginning
                        dstFilePath); // output

                Process videoConverter = videoConversion.start();
                videoConverter.waitFor();
                File outputFile = new File(dstFilePath);
                outputFile.renameTo(new File(finalFilePath));

            } catch (IOException | InterruptedException ex) {
                LOG.warning(ex.getMessage());
                ex.printStackTrace();
            } finally {
                srcFile.delete();
            }
        });
        videoConvertingThread.setDaemon(true);
        videoConvertingThread.start();
    }

    private void checkVideoLength(FileItem fileItem, File srcFile) throws IOException, InterruptedException, ParseException {
        if (fileItem.getName().toLowerCase().endsWith(".webm")) {
            checkWebmLength(fileItem, srcFile);
        } else {
            checkMp4AndMovLength(fileItem, srcFile);
        }
    }

    private void checkWebmLength(FileItem fileItem, File tmpFile) throws IOException, InterruptedException, ParseException {

        String ffmpegPath = properties.getProperty("video.ffmpeg.dir");
        LOG.warning(ffmpegPath);

        ProcessBuilder durationCheck = new ProcessBuilder(//
                "/bin/sh", "-c", //
                ffmpegPath + "/ffmpeg -i " + tmpFile.getAbsolutePath() + " -f null - 2>&1 "
                        + "| grep time= " //
                        + "| awk '{ print $6 }' " //
                        + "| cut -d'=' -f 2"//
        );

        Process videoCheck = durationCheck.start();
        BufferedReader br = new BufferedReader(new InputStreamReader(videoCheck.getInputStream()));
        String durationString = br.readLine();
        videoCheck.waitFor();

        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss.SS");
        Date duration = format.parse(durationString);
        Date allowed = format.parse("00:00:06.00");

        if (duration.after(allowed)) {
            fileItem.delete();
            throw new IOException("Exceeded max time amount of video duration of " + MAX_VIDEO_DURATION
                    + " seconds. Video is: " + duration);
        }
    }

    private void checkMp4AndMovLength(FileItem fileItem, File tmpFile) throws IOException, InterruptedException {

        String ffmpegPath = properties.getProperty("video.ffmpeg.dir");

        ProcessBuilder durationCheck = new ProcessBuilder(//
                "/bin/sh", "-c", //
                ffmpegPath + "/ffprobe " //
                        + "-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 " //
                        + tmpFile.getAbsolutePath());

        Process videoCheck = durationCheck.start();
        BufferedReader br = new BufferedReader(new InputStreamReader(videoCheck.getInputStream()));
        String durationString = br.readLine();
        videoCheck.waitFor();

        if (Double.parseDouble(durationString) > 6.0f) {
            fileItem.delete();
            throw new IOException("Exceeded max time amount of video duration of " + MAX_VIDEO_DURATION + " seconds. Video is: " + durationString);
        }
    }

    private String generateFileNameWithHash(FileItem fileItem, String fileHash) throws UnsupportedEncodingException {
        String contentType = fileItem.getName().substring(fileItem.getName().lastIndexOf(".") + 1);
        String subHash = fileHash.substring(0, 12);

        return subHash + "." + contentType;
    }

    private static boolean matches(String matchHeader, String toMatch) {
        String[] matchValues = matchHeader.split("\\s*,\\s*");
        Arrays.sort(matchValues);
        return Arrays.binarySearch(matchValues, toMatch) > -1 || Arrays.binarySearch(matchValues, "*") > -1;
    }

    private static class Range {
        long start;
        long end;
        long length;
        long total;

        public Range(long start, long end, long total) {
            this.start = start;
            this.end = end;
            this.length = end - start + 1;
            this.total = total;
        }
    }
}
