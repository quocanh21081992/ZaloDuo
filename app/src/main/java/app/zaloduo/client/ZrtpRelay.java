package app.zaloduo.client;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kết nối UDP tới máy chủ trung chuyển cuộc gọi của Zalo ("ZRTP" relay). Định dạng gói theo ctdozlite (call.rs /
 * media.rs, phân tích từ ZaloCall.exe và libzrtc.so):
 * <ul>
 * <li>Đầu gói chung 21 byte: [0x01, type, u32 seq, u32 ssrc/seq, u32 fromId, u32 callId/token, u16 cmd, u8 subCmd] (LE).</li>
 * <li>InitZRTP (type 0x7E, cmd 0x0B gọi đi / 0x0C nhận): + callId, partnerUid, u16 len + sessId. Máy chủ trả res, callId,
 * token, "ip|port" công khai.</li>
 * <li>Echo (cmd 5) đo độ trễ; Ping (cmd 2, callId = token) giữ kết nối; End (cmd 0x20/0x09) kết thúc.</li>
 * <li>Media: [0x03 + u32 token LE] + RTP (âm thanh), 0x05 RTCP âm thanh. Nhận về: 0x04 + RTP hoặc 0x03/0x05 + token + RTP.</li>
 * </ul>
 * Không có SRTP (srtpMode 0): RTP đi thẳng, chỉ bọc đầu gói của relay.
 */
final class ZrtpRelay {
    interface MediaSink {
        /** Gói RTP (đã bỏ lớp bọc relay) nhận được. Chạy trên luồng nhận. */
        void onRtp(byte[] buf, int off, int len);
    }

    static final class Server {
        final String rtp, rtcp; // "ip:port"
        final InetSocketAddress rtpAddr, rtcpAddr;
        volatile int token;
        volatile boolean ok;
        volatile long rttMs = -1;
        volatile int echoRecv;

        Server(String rtp, String rtcp) {
            this.rtp = rtp;
            this.rtcp = rtcp.isEmpty() ? rtp : rtcp;
            this.rtpAddr = parse(this.rtp);
            this.rtcpAddr = parse(this.rtcp);
        }
    }

    private final int fromUid, partnerUid, callId;
    private final boolean caller;
    private final String sessId;
    final List<Server> servers = new ArrayList<>();
    private DatagramSocket rtpSock, rtcpSock;
    private volatile boolean running;
    private volatile MediaSink sink;
    private volatile Server active;
    private volatile String publicAddr;
    private int echoSeq = 1;
    private final long startMs = System.currentTimeMillis();
    // Thống kê media
    volatile long txPkts, rxPkts;

    ZrtpRelay(int fromUid, int partnerUid, int callId, boolean caller, String sessId) {
        this.fromUid = fromUid;
        this.partnerUid = partnerUid;
        this.callId = callId;
        this.caller = caller;
        this.sessId = sessId == null ? "" : sessId;
    }

    /** Cuộc gọi nhóm (0 = 1-1): gói init/ping/kết thúc dạng nhóm (ctdozlite build_zrtp_group_*). */
    private int groupId;

    void setGroup(int gid) { groupId = gid; }

    void addServer(String rtp, String rtcp) {
        if (rtp == null || rtp.trim().isEmpty()) return;
        for (Server s : servers) if (s.rtp.equals(rtp.trim())) return;
        Server s = new Server(rtp.trim(), rtcp == null ? "" : rtcp.trim());
        if (s.rtpAddr != null) servers.add(s);
    }

    int localPort() { return rtpSock == null ? 0 : rtpSock.getLocalPort(); }

    String publicAddr() { return publicAddr; }

    /** Mở socket, gửi InitZRTP + Echo tới mọi máy chủ và chờ trả lời (~waitMs). Trả về true nếu có máy chủ nhận. */
    boolean start(long waitMs) throws SocketException {
        rtpSock = new DatagramSocket();
        rtcpSock = new DatagramSocket();
        running = true;
        startReceiver(rtpSock, "rtp");
        startReceiver(rtcpSock, "rtcp");
        for (int round = 0; round < 2; round++) {
            for (Server s : servers) {
                send(rtpSock, initPacket((byte) 2), s.rtpAddr);
                if (groupId != 0) send(rtpSock, groupInitV6(), s.rtpAddr);
                else if (!s.rtcp.equals(s.rtp) && s.rtcpAddr != null) send(rtcpSock, initPacket((byte) 6), s.rtcpAddr);
                for (int i = 0; i < 7; i++) sendEcho(s);
            }
            sleep(20);
        }
        long until = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < until && firstOk() == null) sleep(20);
        sleep(80); // cho các máy chủ còn lại kịp trả lời
        Thread ka = new Thread(this::keepAlive, "zrtp-keepalive");
        ka.setDaemon(true);
        ka.start();
        Server f = firstOk();
        CallEvents.log("relay probe: servers=" + servers.size() + " ok=" + (f == null ? "none" : f.rtp)
                + " local=" + localPort() + " public=" + publicAddr);
        return f != null;
    }

    Server firstOk() {
        Server best = null;
        for (Server s : servers) if (s.ok && (best == null || (s.rttMs >= 0 && s.rttMs < best.rttMs))) best = s;
        return best;
    }

    Server find(String rtp) {
        if (rtp == null) return null;
        for (Server s : servers) if (s.rtp.equals(rtp.trim())) return s;
        return null;
    }

    /** Chọn máy chủ dùng cho media (theo rtpSerIp trong sự kiện answer, không có thì máy chủ trả lời sớm nhất). */
    Server select(String rtp) {
        Server named = find(rtp);
        Server best = firstOk();
        // Máy chủ được chỉ định đã có token thì dùng; không thì máy chủ trả lời sớm nhất; không có gì thì vẫn thử máy được chỉ định.
        Server s = named != null && named.token != 0 ? named : (best != null ? best : named);
        active = s;
        if (s != null) send(rtpSock, pingPacket(s.token), s.rtpAddr);
        CallEvents.log("relay select " + (s == null ? "none" : s.rtp + " token=" + Integer.toUnsignedString(s.token)));
        return s;
    }

    void setSink(MediaSink sink) { this.sink = sink; }

    /** Gửi một gói RTP âm thanh (bọc 0x03 + token) tới máy chủ đang dùng. */
    void sendAudioRtp(byte[] rtp, int len) {
        Server s = active;
        if (s == null || rtpSock == null) return;
        byte[] wire = new byte[5 + len];
        wire[0] = 0x03;
        putIntLE(wire, 1, s.token);
        System.arraycopy(rtp, 0, wire, 5, len);
        if (send(rtpSock, wire, s.rtpAddr)) txPkts++;
    }

    void stop() {
        if (!running) return;
        running = false;
        for (Server s : servers) send(rtpSock, endPacket(s.token), s.rtpAddr);
        CallEvents.log("relay stop tx=" + txPkts + " rx=" + rxPkts);
        if (rtpSock != null) rtpSock.close();
        if (rtcpSock != null) rtcpSock.close();
    }

    // ------------------------------------------------------------------ luồng

    private void keepAlive() {
        while (running) {
            sleep(400);
            if (!running) break;
            for (Server s : servers) {
                if (s.token == 0) {
                    send(rtpSock, initPacket((byte) 2), s.rtpAddr);
                    if (groupId != 0) send(rtpSock, groupInitV6(), s.rtpAddr);
                } else {
                    send(rtpSock, pingPacket(s.token), s.rtpAddr);
                }
                sendEcho(s);
            }
        }
    }

    private void startReceiver(final DatagramSocket sock, final String name) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[2048];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try { sock.setSoTimeout(500); } catch (SocketException ignored) { }
            while (running) {
                try {
                    p.setLength(buf.length);
                    sock.receive(p);
                    onPacket(name, buf, p.getLength(), p.getAddress(), p.getPort());
                } catch (SocketTimeoutException ignored) {
                    // kiểm tra running
                } catch (IOException e) {
                    if (running) CallEvents.log("relay " + name + " recv error " + e);
                    break;
                } catch (RuntimeException e) {
                    CallEvents.log("relay " + name + " handler error " + e);
                }
            }
        }, "zrtp-rx-" + name);
        t.setDaemon(true);
        t.start();
    }

    // Chẩn đoán: số gói nhận theo (socket, byte đầu, byte thứ hai), in mẫu hex vài gói đầu mỗi loại.
    private final Map<String, Integer> rxKinds = new LinkedHashMap<>();
    private long lastStat;

    private void onPacket(String sockName, byte[] b, int n, InetAddress from, int port) {
        String peer = from.getHostAddress() + ":" + port;
        int t0 = b[0] & 0xFF;
        String kind = sockName + ":" + String.format("%02x%02x", b[0], n > 1 ? b[1] : 0);
        synchronized (rxKinds) {
            Integer c = rxKinds.get(kind);
            rxKinds.put(kind, c == null ? 1 : c + 1);
            if (c == null || c < 2) {
                StringBuilder hx = new StringBuilder();
                for (int i = 0; i < Math.min(n, 48); i++) hx.append(String.format("%02x", b[i]));
                CallEvents.log("relay rx sample " + kind + " len=" + n + " from " + peer + " " + hx);
            }
            long now = System.currentTimeMillis();
            if (now - lastStat > 5000) {
                lastStat = now;
                CallEvents.log("relay rx stats " + rxKinds + " tx=" + txPkts + " media rx=" + rxPkts);
            }
        }
        // Trả lời InitZRTP: [0x01|0x02, 0x7E|0x7F, ..., cmd 0x0B|0x0C ở 18..19], res ở 21..24
        if (n >= 25 && (t0 == 1 || t0 == 2) && ((b[1] & 0xFF) == 0x7E || (b[1] & 0xFF) == 0x7F)) {
            int cmd = (b[18] & 0xFF) | ((b[19] & 0xFF) << 8);
            if (cmd == 0x0B || cmd == 0x0C) {
                int res = getIntLE(b, 21);
                Server s = serverFor(peer);
                if (res == 0 && n >= 33) {
                    int token = getIntLE(b, 29);
                    String pub = null;
                    if (n >= 35) {
                        int alen = (b[33] & 0xFF) | ((b[34] & 0xFF) << 8);
                        if (alen > 0 && n >= 35 + alen) pub = new String(b, 35, alen, StandardCharsets.UTF_8).trim();
                    }
                    if (s != null && s.token == 0) {
                        s.token = token;
                        s.ok = true;
                        if (s.rttMs < 0) s.rttMs = System.currentTimeMillis() - startMs;
                        send(rtpSock, pingPacket(token), s.rtpAddr);
                        if (groupId != 0) send(rtpSock, groupLayersPacket(token), s.rtpAddr);
                    }
                    if (pub != null && publicAddr == null) publicAddr = pub;
                    CallEvents.log("relay init ok " + peer + " via " + sockName + " token=" + Integer.toUnsignedString(token) + " pub=" + pub);
                } else {
                    CallEvents.log("relay init res=" + res + " from " + peer + " via " + sockName);
                }
                return;
            }
        }
        // Echo / điều khiển: [0x01, 0x01, ...] cmd 5
        if (n >= 21 && t0 == 1 && (b[1] & 0xFF) == 1) {
            int cmd = (b[18] & 0xFF) | ((b[19] & 0xFF) << 8);
            if (cmd == 5) {
                Server s = serverFor(peer);
                if (s != null) {
                    s.echoRecv++;
                    if (s.rttMs < 0) s.rttMs = System.currentTimeMillis() - startMs;
                }
            }
            return;
        }
        // Media
        int off;
        switch (t0) {
            case 0x04: case 0x07: case 0x0E: off = 1; break;
            case 0x03: case 0x05: case 0x0D: case 0x0F: off = 5; break;
            default: off = (t0 & 0xC0) == 0x80 ? 0 : -1;
        }
        if (off < 0 || n - off < 8 || (b[off] & 0xC0) != 0x80) return;
        int rawPt = b[off + 1] & 0xFF;
        if (rawPt >= 200 && rawPt <= 207) { // RTCP (SR/RR/SDES/BYE/RTPFB/PSFB)
            MediaSink r = rtcpSink;
            if (r != null) r.onRtp(b, off, n - off);
            return;
        }
        if (n - off < 12) return;
        int pt = rawPt & 0x7F;
        rxPkts++;
        MediaSink k = (t0 == 0x0E || pt == CallVideo.PT_DELTA || pt == CallVideo.PT_KEY) ? videoSink : sink;
        if (k != null) k.onRtp(b, off, n - off);
    }

    private volatile MediaSink videoSink, rtcpSink;
    private int negoSeq = 1;

    /**
     * ZRTP IncallNegotiateInfo (cmd 0x20, sub 0x14) với H264DecCapacityInfo(720p, 30 fps) + NegoNetworkInfo — ZaloCall.exe
     * gửi định kỳ trong cuộc gọi video (ctdozlite build_zrtp_incall_negotiate_packet; ô callId mang uid bên kia).
     */
    void sendVideoNegotiate() {
        Server s = active;
        if (s == null || rtpSock == null) return;
        int seq = negoSeq++;
        ByteBuffer body = ByteBuffer.allocate(11).order(ByteOrder.BIG_ENDIAN);
        body.putInt(seq).put((byte) 2).put(new byte[]{0x0B, 0x02, (byte) 0xD0, 0x1E}).put(new byte[]{0x29, 0x01});
        ByteBuffer bb = ByteBuffer.allocate(25 + 11).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 1).put((byte) 1).putInt(seq).putInt(0).putInt(partnerUid).putInt(s.token)
                .putShort((short) 0x0020).put((byte) 0x14).putInt(11).put(body.array());
        send(rtpSock, bb.array(), s.rtpAddr);
    }

    void setVideoSink(MediaSink s) { videoSink = s; }

    void setRtcpSink(MediaSink s) { rtcpSink = s; }

    /** Gói RTP video: bọc 0x0D + token. */
    void sendVideoRtp(byte[] rtp, int len) {
        sendWrapped((byte) 0x0D, rtp, len);
    }

    /** RTCP: bọc 0x05 (âm thanh) / 0x0F (video) + token. */
    void sendRtcp(byte[] rtcp, int len, boolean video) {
        sendWrapped(video ? (byte) 0x0F : (byte) 0x05, rtcp, len);
    }

    private void sendWrapped(byte type, byte[] data, int len) {
        Server s = active;
        if (s == null || rtpSock == null) return;
        byte[] wire = new byte[5 + len];
        wire[0] = type;
        putIntLE(wire, 1, s.token);
        System.arraycopy(data, 0, wire, 5, len);
        if (send(rtpSock, wire, s.rtpAddr)) txPkts++;
    }

    private Server serverFor(String peer) {
        for (Server s : servers) if (s.rtp.equals(peer) || s.rtcp.equals(peer)) return s;
        return null;
    }

    private void sendEcho(Server s) {
        String ts = Long.toString(System.currentTimeMillis() / 1000);
        send(rtpSock, echoPacket(echoSeq++, ts), s.rtpAddr);
    }

    private boolean send(DatagramSocket sock, byte[] data, InetSocketAddress to) {
        if (sock == null || to == null || sock.isClosed()) return false;
        try {
            sock.send(new DatagramPacket(data, data.length, to));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ gói

    private static ByteBuffer header(int extra, int type, int seq, int ssrc, int from, int callOrToken, int cmd, int sub) {
        ByteBuffer bb = ByteBuffer.allocate(21 + extra).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 1).put((byte) type).putInt(seq).putInt(ssrc).putInt(from).putInt(callOrToken)
                .putShort((short) cmd).put((byte) sub);
        return bb;
    }

    private byte[] initPacket(byte subCmd) {
        byte[] s = sessId.getBytes(StandardCharsets.UTF_8);
        if (groupId != 0) {
            // GroupCallController::_sendRequestInitZRTPToServer: cmd 0x0B sub 4, callId, groupId, sess, [00 03 01 01]
            ByteBuffer bb = header(10 + s.length + 6, 0x7E, 0, 0, fromUid, 0, 0x000B, 4);
            bb.putInt(callId).putInt(groupId).putShort((short) s.length).put(s).putShort((short) 4)
                    .put(new byte[]{0x00, 0x03, 0x01, 0x01});
            return bb.array();
        }
        ByteBuffer bb = header(10 + s.length, 0x7E, 0, 0, fromUid, 0, caller ? 0x0B : 0x0C, subCmd);
        bb.putInt(callId).putInt(partnerUid).putShort((short) s.length).put(s);
        return bb.array();
    }

    /** Biến thể IPv6 của init nhóm (cmd 0x0C sub 6), ZaloCall.exe gửi kèm. */
    private byte[] groupInitV6() {
        byte[] s = sessId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = header(4 + 3 + 2 + s.length + 6, 0x7E, 0, 0, fromUid, 0, 0x000C, 6);
        bb.putInt(callId).put(new byte[]{0x00, 0x03, 0x00}).putShort((short) s.length).put(s).putShort((short) 4)
                .put(new byte[]{0x00, 0x03, 0x01, 0x01});
        return bb.array();
    }

    private byte[] pingPacket(int token) {
        byte[] s = sessId.getBytes(StandardCharsets.UTF_8);
        if (groupId != 0) {
            // ping nhóm: cmd 0x33 sub 1, callId, groupId, sess, [00 03 01]
            ByteBuffer bb = header(10 + s.length + 5, 0x7E, 0, 0, fromUid, token, 0x0033, 0x01);
            bb.putInt(callId).putInt(groupId).putShort((short) s.length).put(s).putShort((short) 3)
                    .put(new byte[]{0x00, 0x03, 0x01});
            return bb.array();
        }
        ByteBuffer bb = header(2 + s.length, 0x01, 0, 0, fromUid, token, 0x0002, 0x01);
        bb.putShort((short) s.length).put(s);
        return bb.array();
    }

    /** Báo 3 lớp video (qualityId 0/3/6) cho SFU nhóm — ZaloCall.exe gửi khi vào nhóm. */
    private byte[] groupLayersPacket(int token) {
        ByteBuffer bb = header(2 + 1 + 27, 0x01, 0, 1, fromUid, token, 0x0020, 0x0C);
        bb.putShort((short) 28).put((byte) 3);
        int[][] layers = {{0, 120, 160, 120}, {3, 300, 320, 240}, {6, 600, 640, 480}};
        for (int[] l : layers) bb.put((byte) l[0]).putInt(l[1]).putShort((short) l[2]).putShort((short) l[3]);
        return bb.array();
    }

    private static byte[] echoPacket(int seq, String ts) {
        byte[] t = ts.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = header(2 + t.length, 0x01, 0, seq, 0x003E9765, 0x012F833C, 0x0005, 0x00);
        bb.putShort((short) t.length).put(t);
        return bb.array();
    }

    private byte[] endPacket(int token) {
        if (groupId != 0) {
            // GroupCallController::_sendRequestZRTPEndCall: type 0x7E, cmd 3 sub 1, callId
            ByteBuffer bb = header(4, 0x7E, 0, 0, fromUid, token, 0x0003, 0x01);
            bb.putInt(callId);
            return bb.array();
        }
        byte[] s = sessId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = header(4 + s.length, 0x01, 0, 0, fromUid, callId, 0x0020, 0x09);
        bb.putInt(s.length).put(s);
        return bb.array();
    }

    // ------------------------------------------------------------------ tiện ích

    static InetSocketAddress parse(String hostPort) {
        if (hostPort == null) return null;
        int i = hostPort.lastIndexOf(':');
        if (i <= 0) return null;
        try {
            return new InetSocketAddress(InetAddress.getByName(hostPort.substring(0, i).trim()),
                    Integer.parseInt(hostPort.substring(i + 1).trim()));
        } catch (Exception e) {
            return null;
        }
    }

    static void putIntLE(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >> 8); b[off + 2] = (byte) (v >> 16); b[off + 3] = (byte) (v >> 24);
    }

    static int getIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    /** Địa chỉ IP nội bộ dùng để ra Internet (không gửi gói nào, chỉ hỏi bảng định tuyến). */
    static String localIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 80);
            InetAddress a = s.getLocalAddress();
            if (a != null && !a.isAnyLocalAddress()) return a.getHostAddress();
        } catch (Exception ignored) { }
        return "127.0.0.1";
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
