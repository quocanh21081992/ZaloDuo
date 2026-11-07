package app.zaloduo.client;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Ghi âm cuộc gọi: trộn tiếng micro của mình (sau khi tắt mic thì là im lặng) với tiếng bên kia (đúng thứ đang phát ra
 * loa), 16 kHz mono, mã hoá AAC 32 kbps vào file .m4a.
 * <p>
 * Nhịp ghi theo micro: mỗi khung 20 ms của micro lấy cùng số mẫu tiếng bên kia từ bộ đệm (thiếu thì im lặng, dồn quá 2 giây
 * thì bỏ phần cũ) — hai bên cùng chạy theo đồng hồ âm thanh của máy nên không lệch dần.
 * <p>
 * File ghi tạm trong thư mục riêng của app; dừng thì chuyển sang Bộ nhớ trong/ZaloDuo/records ({@link Storage}).
 */
final class CallRecorder {
    private static final int RATE = CallAudio.RATE, FRAME = CallAudio.FRAME;
    private static final int REMOTE_MAX = RATE * 2;

    private final Context app;
    private final String title;
    private final File tmp;
    private final BlockingQueue<short[]> frames = new ArrayBlockingQueue<>(500);
    private final short[] remote = new short[REMOTE_MAX];
    private int rHead, rCount;
    private volatile boolean running;
    private Thread encT;
    private final long startedAt = System.currentTimeMillis();

    /** Kết quả khi dừng (luồng nền): đường dẫn hiển thị, hoặc null nếu không ghi được gì. */
    interface Done { void saved(String where); }

    CallRecorder(Context c, String partner) {
        app = c.getApplicationContext();
        String name = partner == null ? "" : partner.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (name.length() > 40) name = name.substring(0, 40);
        title = "ZaloDuo_" + (name.isEmpty() ? "" : name + "_")
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".m4a";
        File dir = new File(app.getCacheDir(), "rec");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        tmp = new File(dir, title);
    }

    long startedAt() { return startedAt; }

    void start() {
        running = true;
        encT = new Thread(this::encodeLoop, "call-record");
        encT.start();
        CallEvents.log("record start " + title);
    }

    /** Khung micro 16 kHz (PCM 16 bit LE, đã tắt tiếng nếu đang tắt mic). */
    void mic(byte[] pcm, int len) {
        if (!running) return;
        int n = len / 2;
        short[] f = new short[n];
        synchronized (remote) {
            for (int i = 0; i < n; i++) {
                int s = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
                if (rCount > 0) {
                    s += remote[rHead];
                    rHead = (rHead + 1) % REMOTE_MAX;
                    rCount--;
                }
                f[i] = (short) Math.max(-32768, Math.min(32767, s));
            }
        }
        frames.offer(f);
    }

    /** Tiếng bên kia đúng như phát ra loa: PCM 16 bit LE ở {@code rate} Hz, {@code ch} kênh — đổi về 16 kHz mono. */
    void remote(byte[] pcm, int off, int len, int rate, int ch) {
        if (!running || rate % RATE != 0) return;
        int step = rate / RATE, frameBytes = 2 * ch, total = len / frameBytes;
        synchronized (remote) {
            for (int i = 0; i < total; i += step) {
                int p = off + i * frameBytes, acc = 0;
                for (int c = 0; c < ch; c++) acc += (short) ((pcm[p + 2 * c] & 0xFF) | (pcm[p + 2 * c + 1] << 8));
                short s = (short) (acc / ch);
                if (rCount == REMOTE_MAX) { rHead = (rHead + 1) % REMOTE_MAX; rCount--; } // dồn quá 2 giây: bỏ cũ
                remote[(rHead + rCount) % REMOTE_MAX] = s;
                rCount++;
            }
        }
    }

    void remoteShorts(short[] pcm, int n) {
        if (!running) return;
        synchronized (remote) {
            for (int i = 0; i < n; i++) {
                if (rCount == REMOTE_MAX) { rHead = (rHead + 1) % REMOTE_MAX; rCount--; }
                remote[(rHead + rCount) % REMOTE_MAX] = pcm[i];
                rCount++;
            }
        }
    }

    /** Dừng, đóng file và lưu vào thư mục ghi âm (luồng nền); báo kết quả qua {@code done}. */
    void stop(Done done) {
        if (!running) return;
        running = false;
        new Thread(() -> {
            try { if (encT != null) encT.join(5000); } catch (InterruptedException ignored) { }
            String where = publish();
            CallEvents.log("record saved " + where);
            if (done != null) done.saved(where);
        }, "call-record-save").start();
    }

    private volatile boolean wroteAny;

    private void encodeLoop() {
        MediaCodec enc = null;
        MediaMuxer mux = null;
        int track = -1;
        boolean muxStarted = false;
        try {
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 32000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * 2 * 4);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mux = new MediaMuxer(tmp.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long samples = 0;
            boolean eosSent = false, eos = false;
            while (!eos) {
                if (!eosSent) {
                    short[] fr = frames.poll(50, TimeUnit.MILLISECONDS);
                    if (fr != null || !running) {
                        int in = enc.dequeueInputBuffer(20000);
                        if (in >= 0) {
                            ByteBuffer ib = enc.getInputBuffer(in);
                            ib.clear();
                            long pts = samples * 1_000_000L / RATE;
                            if (fr == null) { // đã dừng và hết khung: kết thúc luồng
                                enc.queueInputBuffer(in, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                eosSent = true;
                            } else {
                                for (short s : fr) { ib.put((byte) s); ib.put((byte) (s >> 8)); }
                                enc.queueInputBuffer(in, 0, fr.length * 2, pts, 0);
                                samples += fr.length;
                            }
                        } else if (fr != null) {
                            frames.offer(fr); // bộ mã hoá bận: thử lại
                        }
                    }
                }
                int out;
                while ((out = enc.dequeueOutputBuffer(info, eosSent ? 10000 : 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mux.addTrack(enc.getOutputFormat());
                        mux.start();
                        muxStarted = true;
                        continue;
                    }
                    if (out < 0) continue;
                    ByteBuffer ob = enc.getOutputBuffer(out);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && muxStarted) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        mux.writeSampleData(track, ob, info);
                        wroteAny = true;
                    }
                    enc.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { eos = true; break; }
                }
            }
        } catch (Exception e) {
            CallEvents.log("record error " + e);
        } finally {
            if (enc != null) { try { enc.stop(); } catch (Exception ignored) { } enc.release(); }
            if (mux != null) {
                try { if (muxStarted) mux.stop(); } catch (Exception e) { wroteAny = false; }
                mux.release();
            }
        }
    }

    /** Chuyển file tạm vào Bộ nhớ trong/ZaloDuo/records ({@link Storage}); trả về đường dẫn để báo người dùng. */
    private String publish() {
        if (!wroteAny || !tmp.exists() || tmp.length() == 0) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return null;
        }
        try {
            String where = Storage.save(app, Storage.RECORDS, title, "audio/mp4", new FileInputStream(tmp));
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return where;
        } catch (Exception e) {
            CallEvents.log("record publish error " + e);
            return tmp.getAbsolutePath();
        }
    }
}
