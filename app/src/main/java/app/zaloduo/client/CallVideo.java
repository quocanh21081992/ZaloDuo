package app.zaloduo.client;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Hình cuộc gọi video. Định dạng của Zalo (ctdozlite media.rs, libzrtc.so) — không phải RFC 6184:
 * <ul>
 * <li>Mỗi khung hình (access unit) H.264 dạng Annex-B (00 00 00 01 + NAL...) nằm trọn trong một gói RTP, marker = 1.</li>
 * <li>Khung lớn hơn ~1200 byte chia đều thành nhiều mảnh: [0x1C, cờ (0x80 đầu, 0x40 cuối), phần khung]; marker ở mảnh cuối.</li>
 * <li>PT 98 cho khung khoá (có IDR), PT 97 cho khung thường; đồng hồ 90 kHz. Gửi bọc [0x0D + token]; nhận [0x0E] + RTP.</li>
 * <li>Bên kia xin khung khoá bằng RTCP PLI/FIR -> {@link #requestKeyframe}; mình thiếu khung khoá thì gửi PLI.</li>
 * </ul>
 * Hướng gửi: Camera2 -> MediaCodec H.264 (đầu vào là Surface) -> đóng gói. Hướng nhận: ghép mảnh -> MediaCodec -> Surface.
 */
final class CallVideo {
    interface Out {
        void sendVideoRtp(byte[] rtp, int len);
        void sendRtcp(byte[] rtcp, int len, boolean video);
    }

    static final int PT_DELTA = 97, PT_KEY = 98;
    static final int W = 640, H = 480, FPS = 15, BITRATE = 700_000;
    private static final int MAX_PAYLOAD = 1200;

    private final Context ctx;
    private final Out out;
    private final int ssrc, audioSsrc;
    private volatile boolean running;
    private volatile boolean front = true, camOn = true;

    // gửi
    private MediaCodec enc;
    private Surface encSurface;
    private CameraDevice cam;
    private CameraCaptureSession session;
    private HandlerThread camThread;
    private Handler camHandler;
    private volatile Surface previewSurface;
    private byte[] spsPps = new byte[0];
    private int seq = (int) (Math.random() * 65535);
    private long lastKeyReq;
    volatile long txFrames, rxFrames, decFrames, pliSent, pliRecv, rxIdr;

    // nhận
    private volatile Surface remoteSurface;
    private MediaCodec dec;
    private final BlockingQueue<byte[]> rxAu = new ArrayBlockingQueue<>(30);
    private final java.io.ByteArrayOutputStream frag = new java.io.ByteArrayOutputStream();
    private boolean fragOk;
    private int lastRxSeq = -1;
    private volatile int peerSsrc;
    private Thread decT;
    private volatile boolean synced;
    private long lastPli;

    CallVideo(Context ctx, Out out, int audioSsrc) {
        this.ctx = ctx.getApplicationContext();
        this.out = out;
        this.audioSsrc = audioSsrc;
        // Zalo dùng chung SSRC (= uid) cho tiếng và hình, mỗi luồng một dãy số thứ tự (bắt gói của Zalo PC/điện thoại).
        this.ssrc = audioSsrc;
    }

    // ------------------------------------------------------------------ vòng đời

    void start() {
        running = true;
        camThread = new HandlerThread("call-camera");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        camHandler.post(this::startEncoder);
        decT = new Thread(this::decodeLoop, "call-video-dec");
        decT.start();
    }

    void stop() {
        running = false;
        if (camHandler != null) camHandler.post(() -> {
            closeCamera();
            if (enc != null) { try { enc.stop(); } catch (Exception ignored) { } enc.release(); enc = null; }
            if (reader != null) { reader.close(); reader = null; }
            camThread.quitSafely();
        });
        if (decT != null) decT.interrupt();
        CallEvents.log("video stop tx=" + txFrames + " rx=" + rxFrames + " decoded=" + decFrames
                + " pliSent=" + pliSent + " pliRecv=" + pliRecv);
    }

    /** Ô xem trước camera của mình (null = ẩn). */
    void setPreviewSurface(Surface s) {
        previewSurface = s;
        if (running && camHandler != null) camHandler.post(this::restartSession); // đã dừng: luồng camera không còn
    }

    /** Ô hiện hình bên kia (null = đã ẩn). */
    synchronized void setRemoteSurface(Surface s) {
        remoteSurface = s;
        synced = false; // bộ giải mã dựng lại: cần khung khoá
        if (dec != null) { try { dec.stop(); } catch (Exception ignored) { } dec.release(); dec = null; }
        sendPli();
    }

    void switchCamera() {
        front = !front;
        if (camHandler != null) camHandler.post(() -> { closeCamera(); openCamera(); });
    }

    void setCameraOn(boolean on) {
        camOn = on;
        if (camHandler != null) camHandler.post(() -> { if (on) openCamera(); else closeCamera(); });
    }

    boolean cameraOn() { return camOn; }

    // ------------------------------------------------------------------ gửi: camera -> H.264

    /*
     * Camera trả khung hình theo hướng cảm biến (nằm ngang, 640x480); điện thoại cầm dọc thì phải xoay theo
     * SENSOR_ORIENTATION (thường 90 với camera sau, 270 với camera trước) rồi mới mã hoá, không thì bên kia thấy hình
     * nằm ngang. Đường đi: Camera2 -> ImageReader (YUV_420_888) -> xoay -> ảnh đầu vào của MediaCodec (YUV420Flexible,
     * 480x640). Ô xem trước của mình là một đầu ra riêng của camera (Android tự xoay cho TextureView).
     */
    private android.media.ImageReader reader;
    private int sensorRot = 90;
    private final java.util.ArrayDeque<Integer> freeInputs = new java.util.ArrayDeque<>();

    private String pickCamera(CameraManager cm) throws Exception {
        String id = null;
        for (String c : cm.getCameraIdList()) {
            Integer face = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
            if (face != null && face == (front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) { id = c; break; }
        }
        if (id == null && cm.getCameraIdList().length > 0) id = cm.getCameraIdList()[0];
        if (id != null) {
            Integer so = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorRot = so == null ? 90 : so;
        }
        return id;
    }

    private void startEncoder() {
        try {
            try { pickCamera(ctx.getSystemService(CameraManager.class)); } catch (Exception ignored) { }
            boolean portrait = sensorRot == 90 || sensorRot == 270;
            int ow = portrait ? H : W, oh = portrait ? W : H;
            reader = android.media.ImageReader.newInstance(W, H, android.graphics.ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(r -> {
                android.media.Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img != null) encodeFrame(img);
                } catch (Exception e) {
                    CallEvents.log("video frame " + e);
                } finally {
                    if (img != null) img.close();
                }
            }, camHandler);
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ow, oh);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            f.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            enc.setCallback(new MediaCodec.Callback() {
                @Override public void onInputBufferAvailable(MediaCodec c, int i) { freeInputs.add(i); }
                @Override public void onOutputBufferAvailable(MediaCodec c, int i, MediaCodec.BufferInfo info) {
                    try {
                        ByteBuffer b = c.getOutputBuffer(i);
                        byte[] data = new byte[info.size];
                        b.position(info.offset);
                        b.get(data);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) spsPps = data;
                        else if (running && camOn) sendAu(data, (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0, info.presentationTimeUs);
                        c.releaseOutputBuffer(i, false);
                    } catch (Exception e) {
                        CallEvents.log("video enc out " + e);
                    }
                }
                @Override public void onError(MediaCodec c, MediaCodec.CodecException e) { CallEvents.log("video enc error " + e); }
                @Override public void onOutputFormatChanged(MediaCodec c, MediaFormat f2) { }
            }, camHandler);
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encSurface = reader.getSurface();
            enc.start();
            CallEvents.log("video encoder " + enc.getName() + " " + ow + "x" + oh + " sensorRot=" + sensorRot);
            if (camOn) openCamera();
        } catch (Exception e) {
            CallEvents.log("video encoder start failed " + e);
        }
    }

    /** Một khung camera: xoay theo cảm biến, ghi vào ảnh đầu vào của bộ mã hoá. Chạy trên luồng camera. */
    private void encodeFrame(android.media.Image src) {
        MediaCodec e = enc;
        if (e == null || !camOn) return;
        Integer idx = freeInputs.poll();
        if (idx == null) return; // bộ mã hoá còn bận: bỏ khung này
        android.media.Image dst = e.getInputImage(idx);
        if (dst == null) { e.queueInputBuffer(idx, 0, 0, 0, 0); return; }
        int rot = sensorRot;
        int w = src.getWidth(), h = src.getHeight();
        android.media.Image.Plane[] sp = src.getPlanes(), dp = dst.getPlanes();
        rotatePlane(sp[0], w, h, dp[0], rot);
        rotatePlane(sp[1], w / 2, h / 2, dp[1], rot);
        rotatePlane(sp[2], w / 2, h / 2, dp[2], rot);
        e.queueInputBuffer(idx, 0, w * h * 3 / 2, src.getTimestamp() / 1000, 0);
    }

    private byte[] rowBuf = new byte[0];

    /** Chép một mặt phẳng YUV (w x h) sang đích, xoay theo chiều kim đồng hồ rot độ (0/90/180/270). */
    private void rotatePlane(android.media.Image.Plane s, int w, int h, android.media.Image.Plane d, int rot) {
        ByteBuffer sb = s.getBuffer(), db = d.getBuffer();
        int sRow = s.getRowStride(), sPix = s.getPixelStride(), dRow = d.getRowStride(), dPix = d.getPixelStride();
        int need = (w - 1) * sPix + 1;
        if (rowBuf.length < need) rowBuf = new byte[need];
        int dLimit = db.limit();
        for (int y = 0; y < h; y++) {
            int start = y * sRow;
            int len = Math.min(need, sb.limit() - start);
            if (len <= 0) break;
            sb.position(start);
            sb.get(rowBuf, 0, len);
            for (int x = 0; x < w; x++) {
                int si = x * sPix;
                if (si >= len) break;
                int dx, dy;
                switch (rot) {
                    case 90: dx = h - 1 - y; dy = x; break;
                    case 270: dx = y; dy = w - 1 - x; break;
                    case 180: dx = w - 1 - x; dy = h - 1 - y; break;
                    default: dx = x; dy = y;
                }
                int di = dy * dRow + dx * dPix;
                if (di < dLimit) db.put(di, rowBuf[si]);
            }
        }
    }

    @SuppressLint("MissingPermission") // CallActivity xin quyền CAMERA trước khi gọi video
    private void openCamera() {
        if (!running || cam != null || !camOn) return;
        try {
            CameraManager cm = ctx.getSystemService(CameraManager.class);
            String id = pickCamera(cm); // cũng cập nhật sensorRot (camera trước / sau xoay khác nhau)
            if (id == null) { CallEvents.log("no camera"); return; }
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    if (!running || !camOn) { d.close(); return; }
                    cam = d;
                    restartSession();
                    requestKeyframe();
                }
                @Override public void onDisconnected(CameraDevice d) { d.close(); if (cam == d) cam = null; }
                @Override public void onError(CameraDevice d, int err) { CallEvents.log("camera error " + err); d.close(); if (cam == d) cam = null; }
            }, camHandler);
        } catch (Exception e) {
            CallEvents.log("open camera failed " + e);
        }
    }

    private void closeCamera() {
        if (session != null) { try { session.close(); } catch (Exception ignored) { } session = null; }
        if (cam != null) { cam.close(); cam = null; }
    }

    @SuppressWarnings("deprecation")
    private void restartSession() {
        if (cam == null || encSurface == null) return;
        try {
            if (session != null) { session.close(); session = null; }
            List<Surface> outs = new ArrayList<>();
            outs.add(encSurface);
            Surface pv = previewSurface;
            if (pv != null && pv.isValid()) outs.add(pv);
            final CameraDevice d = cam;
            d.createCaptureSession(outs, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (cam != d) { s.close(); return; }
                    session = s;
                    try {
                        CaptureRequest.Builder rb = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        for (Surface o : outs) rb.addTarget(o);
                        rb.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new android.util.Range<>(FPS, FPS));
                        s.setRepeatingRequest(rb.build(), null, camHandler);
                    } catch (Exception e) {
                        CallEvents.log("camera request failed " + e);
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) { CallEvents.log("camera session failed"); }
            }, camHandler);
        } catch (Exception e) {
            CallEvents.log("camera session error " + e);
        }
    }

    /** Bên kia xin khung khoá (PLI/FIR), hoặc vừa bật camera. */
    void requestKeyframe() {
        long now = System.currentTimeMillis();
        if (enc == null || now - lastKeyReq < 300) return;
        lastKeyReq = now;
        try {
            Bundle p = new Bundle();
            p.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            enc.setParameters(p);
        } catch (Exception ignored) { }
    }

    private void sendAu(byte[] data, boolean key, long ptsUs) {
        byte[] au = data;
        if (key && spsPps.length > 0 && !startsWithSps(data)) {
            au = new byte[spsPps.length + data.length];
            System.arraycopy(spsPps, 0, au, 0, spsPps.length);
            System.arraycopy(data, 0, au, spsPps.length, data.length);
        }
        int pt = key ? PT_KEY : PT_DELTA;
        int ts = (int) (ptsUs * 90 / 1000);
        byte[] rtp = new byte[12 + MAX_PAYLOAD + 2];
        if (au.length <= MAX_PAYLOAD) {
            header(rtp, pt, true, ts);
            System.arraycopy(au, 0, rtp, 12, au.length);
            out.sendVideoRtp(rtp, 12 + au.length);
        } else {
            int parts = (au.length + MAX_PAYLOAD - 3) / (MAX_PAYLOAD - 2);
            int chunk = (au.length + parts - 1) / parts;
            int n = (au.length + chunk - 1) / chunk;
            for (int i = 0, off = 0; i < n; i++, off += chunk) {
                int len = Math.min(chunk, au.length - off);
                boolean last = i == n - 1;
                header(rtp, pt, last, ts);
                rtp[12] = 0x1C;
                rtp[13] = (byte) ((i == 0 ? 0x80 : 0) | (last ? 0x40 : 0));
                System.arraycopy(au, off, rtp, 14, len);
                out.sendVideoRtp(rtp, 14 + len);
            }
        }
        txFrames++;
    }

    private static boolean startsWithSps(byte[] d) {
        int i = d.length > 4 && d[2] == 1 ? 3 : 4;
        return d.length > i && (d[i] & 0x1F) == 7;
    }

    private void header(byte[] r, int pt, boolean marker, int ts) {
        r[0] = (byte) 0x80;
        r[1] = (byte) ((marker ? 0x80 : 0) | pt);
        r[2] = (byte) (seq >> 8); r[3] = (byte) seq;
        seq = (seq + 1) & 0xFFFF;
        r[4] = (byte) (ts >> 24); r[5] = (byte) (ts >> 16); r[6] = (byte) (ts >> 8); r[7] = (byte) ts;
        r[8] = (byte) (ssrc >> 24); r[9] = (byte) (ssrc >> 16); r[10] = (byte) (ssrc >> 8); r[11] = (byte) ssrc;
    }

    // ------------------------------------------------------------------ nhận: RTP -> khung -> giải mã

    /** Gói RTP video (PT 97/98) từ relay. Luồng nhận của relay. */
    void onRtp(byte[] b, int off, int len) {
        int cc = b[off] & 0x0F;
        boolean ext = (b[off] & 0x10) != 0;
        int p = off + 12 + cc * 4, end = off + len;
        if (ext) {
            if (p + 4 > end) return;
            int words = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
            p += 4 + words * 4;
        }
        if (p >= end) return;
        peerSsrc = ((b[off + 8] & 0xFF) << 24) | ((b[off + 9] & 0xFF) << 16) | ((b[off + 10] & 0xFF) << 8) | (b[off + 11] & 0xFF);
        int seq16 = ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
        boolean gap = lastRxSeq >= 0 && seq16 != ((lastRxSeq + 1) & 0xFFFF);
        lastRxSeq = seq16;
        synchronized (rx) { rx.update(seq16); }
        int n = end - p;
        boolean annexb = n > 4 && b[p] == 0 && b[p + 1] == 0 && (b[p + 2] == 1 || (b[p + 2] == 0 && b[p + 3] == 1));
        if (annexb) {
            pushAu(Arrays.copyOfRange(b, p, end));
            return;
        }
        if (n >= 2 && (b[p] & 0x1F) == 28 && (b[p + 1] & 0x1F) == 0) {
            int flags = b[p + 1] & 0xFF;
            if ((flags & 0x80) != 0) { frag.reset(); fragOk = true; }
            else if (gap) fragOk = false; // mất mảnh giữa: bỏ cả khung
            if (!fragOk) return;
            frag.write(b, p + 2, n - 2);
            if ((flags & 0x40) != 0) {
                pushAu(frag.toByteArray());
                frag.reset();
                fragOk = false;
            }
        }
    }

    private void pushAu(byte[] au) {
        rxFrames++;
        if (hasNal(au, 5)) rxIdr++; // khung khoá nhận được (nhật ký: bên kia có phát lại khung khoá liên tục không)
        if (!rxAu.offer(au)) { rxAu.poll(); rxAu.offer(au); synced = false; }
    }

    private void decodeLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long pts = 0;
        while (running) {
            try {
                byte[] au = rxAu.poll(200, TimeUnit.MILLISECONDS);
                if (!synced && System.currentTimeMillis() - lastPli > 1000) sendPli();
                if (au == null) continue;
                MediaCodec d;
                synchronized (this) {
                    if (!synced) {
                        if (!hasNal(au, 7) && !hasNal(au, 5)) continue; // chờ khung khoá
                        synced = true;
                    }
                    Surface s = remoteSurface;
                    if (s == null || !s.isValid()) continue;
                    if (dec == null) {
                        dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                        // Cỡ khai báo quyết định bộ đệm vào: 1080p (bên kia có thể gửi 1080p), máy không giải được thì 720p.
                        android.media.MediaCodecInfo.VideoCapabilities vc = dec.getCodecInfo()
                                .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).getVideoCapabilities();
                        boolean fhd = vc != null && vc.isSizeSupported(1920, 1080);
                        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                                fhd ? 1920 : 1280, fhd ? 1080 : 720);
                        // Bộ giải mã hỗ trợ độ trễ thấp (Android 11+): ra hình ngay từng khung, không giữ lại vài khung.
                        if (android.os.Build.VERSION.SDK_INT >= 30 && dec.getCodecInfo()
                                .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                                .isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                            f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                        }
                        dec.configure(f, s, null, 0);
                        dec.start();
                        CallEvents.log("video decoder " + dec.getName());
                    }
                    d = dec;
                }
                int in = d.dequeueInputBuffer(30000);
                if (in >= 0) {
                    ByteBuffer ib = d.getInputBuffer(in);
                    ib.clear();
                    ib.put(au);
                    d.queueInputBuffer(in, 0, au.length, pts, 0);
                    pts += 1_000_000 / FPS;
                }
                int o;
                while ((o = d.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        reportSize(d.getOutputFormat());
                        continue;
                    }
                    if (o < 0) continue;
                    d.releaseOutputBuffer(o, true);
                    decFrames++;
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                CallEvents.log("video decode error " + e);
                synchronized (this) {
                    if (dec != null) { try { dec.release(); } catch (Exception ignored) { } dec = null; }
                    synced = false;
                }
            }
        }
        synchronized (this) {
            if (dec != null) { try { dec.stop(); } catch (Exception ignored) { } dec.release(); dec = null; }
        }
    }

    interface SizeListener {
        /** Kích thước hình bên kia (đã tính vùng cắt và xoay). Luồng chính. */
        void onRemoteSize(int width, int height);
    }

    private volatile SizeListener sizeListener;
    private volatile int remoteW, remoteH;

    /** "rộng×cao" của hình bên kia, null nếu chưa giải mã được khung nào. */
    String remoteSize() { return remoteW > 0 ? remoteW + "×" + remoteH : null; }

    void setSizeListener(SizeListener l) {
        sizeListener = l;
        if (l != null && remoteW > 0) l.onRemoteSize(remoteW, remoteH);
    }

    private void reportSize(MediaFormat f) {
        int w = f.getInteger(MediaFormat.KEY_WIDTH), h = f.getInteger(MediaFormat.KEY_HEIGHT);
        if (f.containsKey("crop-right") && f.containsKey("crop-left")) w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
        if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
        int rot = f.containsKey(MediaFormat.KEY_ROTATION) ? f.getInteger(MediaFormat.KEY_ROTATION) : 0;
        if (rot == 90 || rot == 270) { int t = w; w = h; h = t; }
        remoteW = w;
        remoteH = h;
        CallEvents.log("video remote size " + w + "x" + h + " rot=" + rot);
        final int fw = w, fh = h;
        App.MAIN.post(() -> { SizeListener l = sizeListener; if (l != null) l.onRemoteSize(fw, fh); });
    }

    private static boolean hasNal(byte[] au, int type) {
        for (int i = 0; i + 3 < au.length; i++) {
            if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 1 && (au[i + 3] & 0x1F) == type) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ RTCP

    /** RTCP nhận được (đã bỏ lớp bọc relay): PLI (PSFB fmt 1) / FIR (fmt 4) -> phát khung khoá. */
    void onRtcp(byte[] b, int off, int len) {
        int i = off, end = off + len;
        while (i + 4 <= end) {
            if ((b[i] & 0xC0) != 0x80) return;
            int fmt = b[i] & 0x1F, pt = b[i + 1] & 0xFF;
            int words = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (pt == 206 && (fmt == 1 || fmt == 4)) { pliRecv++; requestKeyframe(); }
            if (pt == 200 && i + 14 <= end) { // SR của bên kia: giữ NTP giữa để báo lại (LSR/DLSR -> bên kia đo RTT)
                synchronized (rx) {
                    rx.lastSr = ((b[i + 10] & 0xFF) << 24) | ((b[i + 11] & 0xFF) << 16) | ((b[i + 12] & 0xFF) << 8) | (b[i + 13] & 0xFF);
                    rx.lastSrAt = System.currentTimeMillis();
                }
            }
            if ((pt == 200 || pt == 201) && (b[i] & 0x1F) > 0) { // khối báo cáo của bên kia về luồng hình mình gửi
                int blk = i + (pt == 200 ? 28 : 8);
                for (int k = 0; k < (b[i] & 0x1F) && blk + 24 <= end; k++, blk += 24) {
                    int s = ((b[blk] & 0xFF) << 24) | ((b[blk + 1] & 0xFF) << 16) | ((b[blk + 2] & 0xFF) << 8) | (b[blk + 3] & 0xFF);
                    if (s == ssrc) peerFraction = b[blk + 4] & 0xFF;
                }
            }
            i += (words + 1) * 4;
        }
    }

    /** Thống kê nhận luồng hình (RFC 3550 A.1, như ctdozlite RxStats): mất gói, số thứ tự cao nhất, SR gần nhất. */
    private static final class RxStats {
        boolean started;
        int baseSeq, maxSeq, cycles, received, expectedPrior, receivedPrior, lastSr;
        volatile int lastFraction; // tỉ lệ mất gói (/256) của chu kỳ báo cáo gần nhất (2 giây)
        long lastSrAt;

        void update(int seq) {
            if (!started) { started = true; baseSeq = maxSeq = seq; }
            else {
                int d = (seq - maxSeq) & 0xFFFF;
                if (d != 0 && d < 0x8000) {
                    if (seq < maxSeq) cycles += 1 << 16;
                    maxSeq = seq;
                }
            }
            received++;
        }

        /** Khối báo cáo 24 byte về {@code ssrc}; chuyển sang chu kỳ mới. */
        void block(java.nio.ByteBuffer b, int ssrc) {
            int extMax = cycles | maxSeq;
            int expected = extMax - baseSeq + 1;
            int lostTotal = Math.max(0, Math.min(expected - received, 0x7FFFFF));
            int expInt = expected - expectedPrior, rcvInt = received - receivedPrior;
            expectedPrior = expected;
            receivedPrior = received;
            int lostInt = Math.max(0, expInt - rcvInt);
            int fraction = expInt <= 0 ? 0 : Math.min(255, (int) ((long) lostInt * 256 / expInt));
            lastFraction = fraction;
            long dlsr = lastSrAt == 0 ? 0 : (System.currentTimeMillis() - lastSrAt) * 65536 / 1000;
            b.putInt(ssrc).put((byte) fraction).put((byte) (lostTotal >> 16)).putShort((short) lostTotal)
                    .putInt(extMax).putInt(0).putInt(lastSr).putInt((int) dlsr);
        }
    }

    private final RxStats rx = new RxStats();
    private int rembLeft = 3;
    private volatile int peerFraction = -1;

    /** % gói hình mình nhận bị mất (chu kỳ 2 giây gần nhất), -1 khi chưa có. */
    int rxLossPct() { synchronized (rx) { return rx.started ? Math.round(rx.lastFraction * 100f / 256) : -1; } }

    /** % gói hình mình gửi bị mất, theo báo cáo (RR/SR) của bên kia; -1 khi bên kia chưa báo. */
    int txLossPct() { int f = peerFraction; return f < 0 ? -1 : Math.round(f * 100f / 256); }

    /**
     * Phản hồi cho bên gửi hình, mỗi 2 giây: RR (mất gói, số thứ tự, LSR/DLSR) + REMB (PSFB fmt 15) mức bitrate tối đa
     * mình nhận được. Bộ điều tiết (GCC) của Zalo cần cả hai để tăng chất lượng: không có RR thì không biết đường truyền
     * tốt, không có REMB thì giữ mức khởi đầu thấp (ctdozlite gửi SR+SDES+REMB mỗi 2 giây).
     */
    void sendFeedback(int bps) {
        int media = peerSsrc;
        if (media == 0) return;
        int exp = 0, mant = bps;
        while (mant > 0x3FFFF && exp < 63) { mant >>= 1; exp++; }
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(8 + 24 + 24); // big-endian
        synchronized (rx) {
            if (rx.started) {
                b.put((byte) 0x81).put((byte) 201).putShort((short) 7).putInt(ssrc);
                rx.block(b, media);
            } else {
                b.put((byte) 0x80).put((byte) 201).putShort((short) 1).putInt(ssrc);
            }
        }
        if (bps > 0 && rembLeft > 0) { // REMB chỉ vài lần đầu (gửi liên tục làm bên kia khựng hình), sau đó chỉ RR
            rembLeft--;
            b.put((byte) 0x8F).put((byte) 206).putShort((short) 5).putInt(ssrc).putInt(0)  // PSFB fmt 15
                    .put(new byte[]{'R', 'E', 'M', 'B'}).put((byte) 1)
                    .put((byte) ((exp << 2) | ((mant >> 16) & 0x03))).putShort((short) mant).putInt(media);
        }
        out.sendRtcp(b.array(), b.position(), true);
    }

    private void sendPli() {
        lastPli = System.currentTimeMillis();
        int media = peerSsrc;
        if (media == 0) return;
        byte[] r = new byte[12];
        r[0] = (byte) 0x81; r[1] = (byte) 206; r[2] = 0; r[3] = 2;
        r[4] = (byte) (ssrc >> 24); r[5] = (byte) (ssrc >> 16); r[6] = (byte) (ssrc >> 8); r[7] = (byte) ssrc;
        r[8] = (byte) (media >> 24); r[9] = (byte) (media >> 16); r[10] = (byte) (media >> 8); r[11] = (byte) media;
        out.sendRtcp(r, r.length, true);
        pliSent++;
    }
}
