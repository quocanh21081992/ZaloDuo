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
    volatile long txFrames, rxFrames, decFrames, pliSent, pliRecv;

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
            camThread.quitSafely();
        });
        if (decT != null) decT.interrupt();
        CallEvents.log("video stop tx=" + txFrames + " rx=" + rxFrames + " decoded=" + decFrames
                + " pliSent=" + pliSent + " pliRecv=" + pliRecv);
    }

    /** Ô xem trước camera của mình (null = ẩn). */
    void setPreviewSurface(Surface s) {
        previewSurface = s;
        if (camHandler != null) camHandler.post(this::restartSession);
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

    private void startEncoder() {
        try {
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            enc.setCallback(new MediaCodec.Callback() {
                @Override public void onInputBufferAvailable(MediaCodec c, int i) { }
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
            encSurface = enc.createInputSurface();
            enc.start();
            CallEvents.log("video encoder " + enc.getName() + " " + W + "x" + H);
            if (camOn) openCamera();
        } catch (Exception e) {
            CallEvents.log("video encoder start failed " + e);
        }
    }

    @SuppressLint("MissingPermission") // CallActivity xin quyền CAMERA trước khi gọi video
    private void openCamera() {
        if (!running || cam != null || !camOn) return;
        try {
            CameraManager cm = ctx.getSystemService(CameraManager.class);
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Integer face = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (face != null && face == (front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) { id = c; break; }
            }
            if (id == null && cm.getCameraIdList().length > 0) id = cm.getCameraIdList()[0];
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
                        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720);
                        dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
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
                while ((o = d.dequeueOutputBuffer(info, 0)) >= 0) {
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
            i += (words + 1) * 4;
        }
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
