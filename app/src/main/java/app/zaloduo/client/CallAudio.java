package app.zaloduo.client;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Âm thanh cuộc gọi: micro (VOICE_COMMUNICATION, có khử tiếng vọng) -> Opus 16 kHz mono 20 ms -> RTP PT 112 -> relay;
 * RTP nhận về -> Opus -> loa thoại. Opus dùng MediaCodec của Android (bộ mã hoá có từ Android 10).
 * Gói nhận về qua bộ đệm chống giật ({@link Jitter}) trước khi giải mã.
 */
final class CallAudio {
    interface Sender {
        void sendRtp(byte[] rtp, int len);
    }

    static final int RATE = 16000;
    static final int FRAME = 320; // 20 ms
    static final int PT = 112;

    private final Sender sender;
    private final int ssrc;
    private volatile boolean running, muted;
    private Thread capT, playT;
    volatile long encoded, decoded, rxDropped;

    /** Gọi nhóm: gói gửi có phần mở rộng RTP; tiếng nhận về trộn từ nhiều người (mỗi SSRC một bộ đệm + bộ giải mã). */
    private final boolean group;
    private final java.util.concurrent.ConcurrentHashMap<Integer, Jitter> peers = new java.util.concurrent.ConcurrentHashMap<>();

    CallAudio(Sender sender, int ssrc) {
        this(sender, ssrc, false);
    }

    CallAudio(Sender sender, int ssrc, boolean group) {
        this.sender = sender;
        this.ssrc = ssrc;
        this.group = group;
    }

    /** Số người đang có tiếng gửi tới (gọi nhóm). */
    int peerCount() { return peers.size(); }

    static boolean supported() {
        try {
            MediaCodec c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            c.release();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void setMuted(boolean m) { muted = m; }

    /** Máy có bộ mã hoá Opus của hệ thống (Android 10+) thì dùng MediaCodec; không thì Concentus (Opus thuần Java). */
    private static Boolean nativeOpus;

    static synchronized boolean nativeOpus() {
        if (nativeOpus == null) nativeOpus = android.os.Build.VERSION.SDK_INT >= 29 && supported();
        return nativeOpus;
    }

    void start() {
        running = true;
        boolean nat = nativeOpus();
        capT = new Thread(nat ? this::captureLoop : this::captureLoopJava, "call-capture");
        playT = new Thread(group ? this::playLoopGroup : nat ? this::playLoop : this::playLoopJava, "call-play");
        capT.start();
        playT.start();
    }

    void stop() {
        running = false;
        if (capT != null) capT.interrupt();
        if (playT != null) playT.interrupt();
        CallEvents.log("audio stop encoded=" + encoded + " decoded=" + decoded + " dropped=" + rxDropped);
    }

    /** Gói RTP nhận được (luồng nhận của relay): lấy phần Opus, đưa vào hàng đợi phát. */
    void onRtp(byte[] b, int off, int len) {
        if ((b[off + 1] & 0x7F) != PT) return;
        int cc = b[off] & 0x0F;
        boolean ext = (b[off] & 0x10) != 0, pad = (b[off] & 0x20) != 0;
        int p = off + 12 + cc * 4, end = off + len;
        if (ext) {
            if (p + 4 > end) return;
            int words = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
            p += 4 + words * 4;
        }
        if (pad && end > p) {
            int padLen = b[end - 1] & 0xFF;
            if (padLen > 0 && padLen <= end - p) end -= padLen;
        }
        if (p >= end) return;
        byte[] opus = new byte[end - p];
        System.arraycopy(b, p, opus, 0, opus.length);
        int seq16 = ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
        if (group) {
            int from = ((b[off + 8] & 0xFF) << 24) | ((b[off + 9] & 0xFF) << 16) | ((b[off + 10] & 0xFF) << 8) | (b[off + 11] & 0xFF);
            if (from == ssrc) return; // tiếng của chính mình (SFU có thể dội lại)
            Jitter j = peers.get(from);
            if (j == null) {
                j = new Jitter();
                Jitter prev = peers.putIfAbsent(from, j);
                if (prev != null) j = prev;
                else CallEvents.log("group audio from ssrc " + Integer.toUnsignedString(from));
            }
            j.put(seq16, opus);
            return;
        }
        jitter.put(seq16, opus);
    }

    /** Gọi nhóm: mỗi 20 ms lấy một gói của từng người, giải mã (Concentus, 16 kHz), cộng lại rồi phát. */
    private void playLoopGroup() {
        AudioTrack track = null;
        java.util.HashMap<Integer, io.github.jaredmdobson.concentus.OpusDecoder> decs = new java.util.HashMap<>();
        try {
            track = newTrack(RATE, 1);
            short[] mix = new short[FRAME];
            short[] one = new short[RATE * 120 / 1000];
            byte[] out = new byte[FRAME * 2];
            int[] acc = new int[FRAME];
            while (running) {
                java.util.Arrays.fill(acc, 0);
                boolean any = false;
                for (java.util.Map.Entry<Integer, Jitter> e : peers.entrySet()) {
                    byte[] pkt = e.getValue().takeNow();
                    if (pkt == null) continue;
                    io.github.jaredmdobson.concentus.OpusDecoder d = decs.get(e.getKey());
                    if (d == null) { d = new io.github.jaredmdobson.concentus.OpusDecoder(RATE, 1); decs.put(e.getKey(), d); }
                    int n;
                    try { n = d.decode(pkt, 0, pkt.length, one, 0, one.length, false); } catch (Exception ex) { continue; }
                    for (int i = 0; i < Math.min(n, FRAME); i++) acc[i] += one[i];
                    any = true;
                    decoded++;
                }
                for (int i = 0; i < FRAME; i++) mix[i] = (short) Math.max(-32768, Math.min(32767, acc[i]));
                for (int i = 0; i < FRAME; i++) { out[2 * i] = (byte) mix[i]; out[2 * i + 1] = (byte) (mix[i] >> 8); }
                // write chặn theo nhịp phát của loa (~20 ms/khung) nên vòng lặp tự đều nhịp; chưa ai nói thì phát im lặng.
                track.write(out, 0, out.length);
                if (!any && peers.isEmpty()) Thread.sleep(5);
            }
        } catch (InterruptedException ignored) {
            // dừng
        } catch (Exception e) {
            if (running) CallEvents.log("group audio play error " + e);
        } finally {
            if (track != null) { try { track.stop(); } catch (Exception ignored) { } track.release(); }
        }
    }

    /**
     * Bộ đệm chống giật: sắp gói theo số thứ tự RTP, chờ trước PREBUFFER gói (60 ms) rồi phát đều; gói tới sau khi chỗ
     * của nó đã phát qua thì bỏ; dồn quá MAX gói (300 ms, ví dụ sau một lúc mạng nghẽn) thì bỏ bớt gói cũ cho đỡ trễ.
     * Hết gói thì chờ đầy lại PREBUFFER.
     */
    private final Jitter jitter = new Jitter();

    private final class Jitter {
        static final int PREBUFFER = 3, MAX = 15;
        private final java.util.TreeMap<Long, byte[]> buf = new java.util.TreeMap<>();
        private long lastExt = -1;   // số thứ tự mở rộng (không quay vòng) của gói mới nhất nhận được
        private long nextPlay = -1;  // gói phải phát tiếp theo
        private boolean primed;

        synchronized void put(int seq16, byte[] data) {
            long ext;
            if (lastExt < 0) ext = seq16;
            else {
                long base = lastExt & ~0xFFFFL;
                ext = base | seq16;
                if (ext - lastExt > 32768) ext -= 65536;
                else if (lastExt - ext > 32768) ext += 65536;
            }
            if (ext > lastExt) lastExt = ext;
            if (nextPlay >= 0 && ext < nextPlay) { rxDropped++; return; } // trễ, đã qua lượt
            buf.put(ext, data);
            while (buf.size() > MAX) { buf.pollFirstEntry(); rxDropped++; }
            if (!primed && buf.size() >= PREBUFFER) primed = true;
            notifyAll();
        }

        /** Như take nhưng không chờ (vòng trộn tiếng nhóm). */
        synchronized byte[] takeNow() {
            if (!primed || buf.isEmpty()) return null;
            java.util.Map.Entry<Long, byte[]> e = buf.pollFirstEntry();
            nextPlay = e.getKey() + 1;
            if (buf.isEmpty()) primed = false;
            return e.getValue();
        }

        /** Gói kế tiếp theo thứ tự, chờ tối đa waitMs; null nếu chưa có (đang chờ đầy bộ đệm hoặc hết gói). */
        synchronized byte[] take(long waitMs) throws InterruptedException {
            long until = System.currentTimeMillis() + waitMs;
            while (!primed || buf.isEmpty()) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) return null;
                wait(left);
            }
            java.util.Map.Entry<Long, byte[]> e = buf.pollFirstEntry();
            nextPlay = e.getKey() + 1;
            if (buf.isEmpty()) primed = false; // cạn: chờ đầy lại
            return e.getValue();
        }
    }

    // ------------------------------------------------------------------ thu + mã hoá

    @SuppressLint("MissingPermission") // CallActivity xin quyền RECORD_AUDIO trước khi gọi
    private void captureLoop() {
        AudioRecord rec = null;
        MediaCodec enc = null;
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        AutomaticGainControl agc = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 2 * 4));
            int sid = rec.getAudioSessionId();
            if (AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(sid); if (aec != null) aec.setEnabled(true); }
            if (NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(sid); if (ns != null) ns.setEnabled(true); }
            if (AutomaticGainControl.isAvailable()) { agc = AutomaticGainControl.create(sid); if (agc != null) agc.setEnabled(true); }

            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 24000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * 2);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            rec.startRecording();
            CallEvents.log("audio capture start codec=" + enc.getName() + " aec=" + (aec != null) + " ns=" + (ns != null));

            byte[] pcm = new byte[FRAME * 2];
            byte[] rtp = new byte[12 + 1500];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long frames = 0;
            int seq = (int) (Math.random() * 65535);
            int tsBase = (int) (Math.random() * Integer.MAX_VALUE);
            while (running) {
                int got = 0;
                while (got < pcm.length && running) {
                    int r = rec.read(pcm, got, pcm.length - got);
                    if (r < 0) throw new IllegalStateException("AudioRecord.read " + r);
                    got += r;
                }
                if (!running) break;
                if (muted) java.util.Arrays.fill(pcm, (byte) 0);
                int in = enc.dequeueInputBuffer(20000);
                if (in >= 0) {
                    ByteBuffer ib = enc.getInputBuffer(in);
                    ib.clear();
                    ib.put(pcm);
                    enc.queueInputBuffer(in, 0, pcm.length, frames * 20000L, 0);
                    frames++;
                }
                int out;
                while ((out = enc.dequeueOutputBuffer(info, 0)) >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(out);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && info.size <= 1500) {
                        int ts = tsBase + (int) (info.presentationTimeUs * RATE / 1_000_000L);
                        int h = rtpHeader(rtp, seq, ts);
                        ob.position(info.offset);
                        ob.get(rtp, h, info.size);
                        sender.sendRtp(rtp, h + info.size);
                        seq = (seq + 1) & 0xFFFF;
                        encoded++;
                    }
                    enc.releaseOutputBuffer(out, false);
                }
            }
        } catch (Exception e) {
            if (running) CallEvents.log("audio capture error " + e);
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
            if (enc != null) { try { enc.stop(); } catch (Exception ignored) { } enc.release(); }
            if (aec != null) aec.release();
            if (ns != null) ns.release();
            if (agc != null) agc.release();
        }
    }

    /** Như captureLoop nhưng mã hoá bằng Concentus (máy chưa có Opus của hệ thống). */
    @SuppressLint("MissingPermission")
    private void captureLoopJava() {
        AudioRecord rec = null;
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 2 * 4));
            int sid = rec.getAudioSessionId();
            if (AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(sid); if (aec != null) aec.setEnabled(true); }
            if (NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(sid); if (ns != null) ns.setEnabled(true); }
            io.github.jaredmdobson.concentus.OpusEncoder enc = new io.github.jaredmdobson.concentus.OpusEncoder(
                    RATE, 1, io.github.jaredmdobson.concentus.OpusApplication.OPUS_APPLICATION_VOIP);
            enc.setBitrate(24000);
            enc.setComplexity(5);
            enc.setSignalType(io.github.jaredmdobson.concentus.OpusSignal.OPUS_SIGNAL_VOICE);
            rec.startRecording();
            CallEvents.log("audio capture start codec=concentus aec=" + (aec != null) + " ns=" + (ns != null));
            byte[] pcm = new byte[FRAME * 2];
            byte[] rtp = new byte[12 + 1500];
            int seq = (int) (Math.random() * 65535);
            int ts = (int) (Math.random() * Integer.MAX_VALUE);
            while (running) {
                int got = 0;
                while (got < pcm.length && running) {
                    int r = rec.read(pcm, got, pcm.length - got);
                    if (r < 0) throw new IllegalStateException("AudioRecord.read " + r);
                    got += r;
                }
                if (!running) break;
                if (muted) java.util.Arrays.fill(pcm, (byte) 0);
                int h = headerLen();
                int n = enc.encode(pcm, 0, FRAME, rtp, h, 1500);
                if (n > 0) {
                    rtpHeader(rtp, seq, ts);
                    sender.sendRtp(rtp, h + n);
                    seq = (seq + 1) & 0xFFFF;
                    encoded++;
                }
                ts += FRAME;
            }
        } catch (Exception e) {
            if (running) CallEvents.log("audio capture error " + e);
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
            if (aec != null) aec.release();
            if (ns != null) ns.release();
        }
    }

    private int headerLen() { return group ? 28 : 12; }

    /**
     * Đầu gói RTP; trả về độ dài. Gọi nhóm: thêm phần mở rộng một-byte (0xBEDE, 3 từ) như ZaloCall.exe gửi tới SFU:
     * ID 2 mức âm lượng (0x20 0x4A) + ID 13 giờ NTP 56 bit (0xD6, giây 24 bit, phần lẻ 32 bit) + 2 byte đệm.
     */
    private int rtpHeader(byte[] rtp, int seq, int ts) {
        rtp[0] = (byte) (group ? 0x90 : 0x80);
        rtp[1] = (byte) PT;
        rtp[2] = (byte) (seq >> 8); rtp[3] = (byte) seq;
        rtp[4] = (byte) (ts >> 24); rtp[5] = (byte) (ts >> 16); rtp[6] = (byte) (ts >> 8); rtp[7] = (byte) ts;
        rtp[8] = (byte) (ssrc >> 24); rtp[9] = (byte) (ssrc >> 16); rtp[10] = (byte) (ssrc >> 8); rtp[11] = (byte) ssrc;
        if (!group) return 12;
        long ms = System.currentTimeMillis();
        long sec = ms / 1000 + 2208988800L;
        long frac = ((ms % 1000) << 32) / 1000;
        rtp[12] = (byte) 0xBE; rtp[13] = (byte) 0xDE; rtp[14] = 0; rtp[15] = 3; // 3 từ = 12 byte
        rtp[16] = 0x20; rtp[17] = 0x4A;
        rtp[18] = (byte) 0xD6;
        rtp[19] = (byte) (sec >> 16); rtp[20] = (byte) (sec >> 8); rtp[21] = (byte) sec;
        rtp[22] = (byte) (frac >> 24); rtp[23] = (byte) (frac >> 16); rtp[24] = (byte) (frac >> 8); rtp[25] = (byte) frac;
        rtp[26] = 0; rtp[27] = 0;
        return 28;
    }

    /** Như playLoop nhưng giải mã bằng Concentus, phát 16 kHz. */
    private void playLoopJava() {
        AudioTrack track = null;
        try {
            io.github.jaredmdobson.concentus.OpusDecoder dec = new io.github.jaredmdobson.concentus.OpusDecoder(RATE, 1);
            byte[] pcm = new byte[RATE * 2 * 120 / 1000]; // tối đa 120 ms
            while (running) {
                byte[] pkt = jitter.take(100);
                if (pkt == null) continue;
                int samples;
                try {
                    samples = dec.decode(pkt, 0, pkt.length, pcm, 0, pcm.length / 2, false);
                } catch (Exception e) {
                    continue; // gói hỏng: bỏ qua
                }
                if (samples <= 0) continue;
                if (track == null) track = newTrack(RATE, 1);
                track.write(pcm, 0, samples * 2);
                decoded++;
            }
        } catch (InterruptedException ignored) {
            // dừng
        } catch (Exception e) {
            if (running) CallEvents.log("audio play error " + e);
        } finally {
            if (track != null) { try { track.stop(); } catch (Exception ignored) { } track.release(); }
        }
    }

    // ------------------------------------------------------------------ giải mã + phát

    private void playLoop() {
        MediaCodec dec = null;
        AudioTrack track = null;
        try {
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 1);
            f.setByteBuffer("csd-0", opusHead());
            f.setByteBuffer("csd-1", longBuf(0));
            f.setByteBuffer("csd-2", longBuf(80_000_000L));
            dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            dec.configure(f, null, null, 0);
            dec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long pts = 0;
            int outRate = 48000, outCh = 1;
            byte[] pcm = new byte[48000 * 2 * 2 / 10];
            while (running) {
                byte[] pkt = jitter.take(100);
                if (pkt != null) {
                    int in = dec.dequeueInputBuffer(20000);
                    if (in >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(in);
                        ib.clear();
                        ib.put(pkt);
                        dec.queueInputBuffer(in, 0, pkt.length, pts, 0);
                        pts += 20000;
                    }
                }
                int out;
                while ((out = dec.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = dec.getOutputFormat();
                        outRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        outCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (track != null) { track.release(); track = null; }
                        continue;
                    }
                    if (out < 0) continue;
                    if (track == null) track = newTrack(outRate, outCh);
                    ByteBuffer ob = dec.getOutputBuffer(out);
                    if (info.size > 0) {
                        if (pcm.length < info.size) pcm = new byte[info.size];
                        ob.position(info.offset);
                        ob.get(pcm, 0, info.size);
                        track.write(pcm, 0, info.size);
                        decoded++;
                    }
                    dec.releaseOutputBuffer(out, false);
                }
            }
        } catch (InterruptedException ignored) {
            // dừng
        } catch (Exception e) {
            if (running) CallEvents.log("audio play error " + e);
        } finally {
            if (dec != null) { try { dec.stop(); } catch (Exception ignored) { } dec.release(); }
            if (track != null) { try { track.stop(); } catch (Exception ignored) { } track.release(); }
        }
    }

    private static AudioTrack newTrack(int rate, int ch) {
        int chMask = ch == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(rate, chMask, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(chMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min, rate * ch * 2 * 100 / 1000)) // ~100 ms: bộ đệm chống giật lo phần còn lại
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        t.play();
        CallEvents.log("audio play start rate=" + rate + " ch=" + ch);
        return t;
    }

    /** OpusHead (RFC 7845) cho bộ giải mã: 1 kênh, pre-skip 312, tần số gốc 16 kHz. */
    private static ByteBuffer opusHead() {
        ByteBuffer b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        b.put("OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.put((byte) 1).put((byte) 1).putShort((short) 312).putInt(RATE).putShort((short) 0).put((byte) 0);
        b.flip();
        return b;
    }

    private static ByteBuffer longBuf(long v) {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        b.putLong(v);
        b.flip();
        return b;
    }
}
