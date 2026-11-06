/*
 * Chèn vào trang Zalo Web (*.zalo.me) trước mọi script của trang.
 * Việc của nó:
 *   1. Ẩn/hiện: cho Zalo biết app đang ở nền để nó bắn thông báo như trên PC.
 *   2. Notification API: WebView Android không có — tự dựng lại và chuyển sang thông báo Android.
 *   3. Trông như Chrome trên Windows (navigator.platform, userAgentData).
 *   4. Theo dõi micro/camera để biết đang trong cuộc gọi.
 *   5. Đoán cuộc gọi đến: âm thanh lặp vang lên khi app đang ở nền.
 *   6. Tải tệp blob:/data: qua app.
 *   7. Ép bề rộng trang (viewport) theo cài đặt.
 *   8. Giao diện điện thoại.
 *   9. Màu thanh trạng thái / thanh điều hướng theo giao diện sáng/tối của Zalo.
 * Không đụng tới nội dung tin nhắn, không gửi dữ liệu đi đâu ngoài máy.
 */
(function () {
  'use strict';
  if (window.__zw) return;
  var cfg = window.__ZW_CFG || {};
  // Mã bí mật cho ZaloBridge (JsBridge.ok): giữ trong closure, gỡ khỏi window để không nằm lại trên trang.
  var BT = String(cfg.bt || '');
  try { delete window.__ZW_CFG; } catch (e) { /* ignore */ }

  // Cầu nối Android (window.ZaloBridge) có thể chưa sẵn sàng đúng lúc script này chạy (tùy bản
  // WebView của máy). Không bỏ cuộc: mọi lời gọi đi qua lớp trung gian, tra lại ZaloBridge mỗi lần.
  var bridge = {};
  ['showNotification', 'cancelNotification', 'permission', 'requestPermission', 'isHidden', 'mediaActive',
    'ringing', 'saveFile', 'shareFile', 'log', 'mobileState', 'systemBars', 'pageReady', 'diag', 'convList',
    'nativeLayout', 'connState', 'needLogin', 'prefetch', 'dockState', 'connLog', 'convOpened', 'showKeyboard',
    'callEvent', 'callSigResult', 'startCall', 'startGroupCall'].forEach(function (m) {
    bridge[m] = function () {
      var b = window.ZaloBridge;
      if (!b || typeof b[m] !== 'function') return m === 'permission' ? 'default' : undefined;
      return b[m].apply(b, [BT].concat([].slice.call(arguments)));
    };
  });

  function log(msg) { try { bridge.log(String(msg)); } catch (e) { /* ignore */ } }
  /** Nhật ký chẩn đoán thông báo của app (Cài đặt → Sao chép nhật ký). Chỉ loại sự kiện, không có nội dung tin. */
  function diag(event) { try { bridge.diag(String(event)); } catch (e) { /* ignore */ } }
  /** Nhật ký kết nối của app (Cài đặt → Sao chép nhật ký kết nối, mục 11). Không có nội dung tin. */
  function connLog(event) { try { bridge.connLog(String(event)); } catch (e) { /* ignore */ } }
  function safe(fn) {
    try { return fn(); } catch (e) { log('shim error: ' + (e && e.stack ? e.stack : e)); }
    return undefined;
  }

  /*
   * Biểu tượng cảm xúc cổ điển của Zalo gõ dạng mã (/-strong, :>, :-((...): trang tự vẽ bằng ảnh, còn thông báo và dòng
   * xem trước chỉ có chữ — đổi sang emoji Unicode gần nghĩa nhất. Chỉ đổi cả "từ" (cách nhau bởi khoảng trắng) mà ghép
   * trọn được từ các mã (vd. "/-strong/-strong"), để không đụng link hay chữ thường có dấu ":".
   */
  var ZEMO = {
    '/-strong': '👍', '/-weak': '👎', '/-heart': '❤️', '/-break': '💔', '/-ok': '👌', '/-v': '✌️', '/-thanks': '🙏',
    '_()_': '🙏', '/-punch': '👊', '/-loveu': '🤟', '/-no': '🙅', '/-bad': '👎', '/-share': '🤝', '/-showlove': '🥰',
    '/-beer': '🍺', '/-coffee': '☕', '/-rose': '🌹', '/-fade': '🥀', '/-bd': '🎂', '/-cake': '🍰', '/-bome': '💣',
    '/-shit': '💩', '/-li': '⚡', '/-flag': '🚩',
    ':)': '🙂', ':))': '😆', ':d': '😃', ';d': '😁', ';-d': '😁', ':>': '😄', ':(': '🙁', ':((': '😢', ':-((': '😭',
    ":')": '😂', ':p': '😛', ';p': '😜', ';-)': '😉', ':o': '😮', ';o': '😲', ':-o': '😯', ':-*': '😘', ':*': '😘',
    ':-h': '😡', '>-|': '😠', ':$': '😳', ':z': '😴', '|-)': '😪', '8-)': '😎', 'b-)': '😎', ':|': '😐', ':-|': '😐',
    ':--|': '😑', ':-<': '😞', '--b': '😓', ':wipe': '😅', ':!': '😱', 'x-)': '😵', ';xx': '😵', ';-s': '😖',
    ';-x': '🤐', ';?': '🤔', '&-(': '😩', 'p-(': '😟', ';-/': '😕', ':-bye': '👋', ':handclap': '👏', '$-)': '🤑',
    ':3': '😚', ':v': '😋'
  };
  var ZEMO_KEYS = Object.keys(ZEMO).sort(function (a, b) { return b.length - a.length; });
  function zEmoji(text) {
    if (!text || typeof text !== 'string') return text;
    return text.replace(/\S+/g, function (w) {
      var low = w.toLowerCase(), out = '', i = 0;
      while (i < low.length) {
        var k = null;
        for (var j = 0; j < ZEMO_KEYS.length; j++) {
          if (low.substr(i, ZEMO_KEYS[j].length) === ZEMO_KEYS[j]) { k = ZEMO_KEYS[j]; break; }
        }
        if (!k) return w; // có phần không phải mã: giữ nguyên từ
        out += ZEMO[k];
        i += k.length;
      }
      return out;
    });
  }

  var api = {};
  Object.defineProperty(window, '__zw', { value: api, configurable: false, enumerable: false, writable: false });

  // ------------------------------------------------------------------ 1. Ẩn/hiện trang
  var forcedHidden = null;
  safe(function () { var h = bridge.isHidden(); if (h === true || h === false) forcedHidden = h; });

  var docProto = Document.prototype;
  var hiddenDesc = Object.getOwnPropertyDescriptor(docProto, 'hidden');
  var origHasFocus = docProto.hasFocus;

  function isHidden(doc) {
    if (forcedHidden !== null) return forcedHidden;
    return hiddenDesc && hiddenDesc.get ? !!hiddenDesc.get.call(doc || document) : false;
  }

  safe(function () {
    Object.defineProperty(docProto, 'hidden', {
      configurable: true, enumerable: true,
      get: function () { return isHidden(this); }
    });
    Object.defineProperty(docProto, 'visibilityState', {
      configurable: true, enumerable: true,
      get: function () { return isHidden(this) ? 'hidden' : 'visible'; }
    });
    docProto.hasFocus = function () {
      if (forcedHidden === true) return false;
      return origHasFocus.call(this);
    };
  });

  api.setHidden = function (hidden) {
    hidden = !!hidden;
    if (forcedHidden === hidden) return;
    forcedHidden = hidden;
    if (!hidden) stopRing();
    if (!hidden && api.reportConv) api.reportConv(); // mở lại app đúng lúc đang ở một hội thoại
    safe(function () {
      document.dispatchEvent(new Event('visibilitychange'));
      window.dispatchEvent(new Event(hidden ? 'blur' : 'focus'));
    });
  };
  api.isHidden = function () { return isHidden(document); };

  /*
   * Zalo Web chỉ cho một tab hoạt động: mã phiên của tab lưu ở localStorage (sh_singleton_web_app). Trang mở lên trong
   * trạng thái ẩn mà thấy mã của "tab khác" thì tự khoá ("Bạn đang mở Zalo trên một Tab khác… Kích hoạt") và ngừng nhận
   * tin. Trong app chỉ có một trang Zalo, mã đó luôn là đồ cũ của lần chạy trước (app bị tắt đột ngột, rồi chạy ngầm
   * sau khi bật máy): xoá trước khi Zalo đọc. Không làm trong popup, vì xoá lúc trang chính đang ẩn sẽ khiến nó tự khoá.
   */
  safe(function () {
    if (window.opener || window.top !== window) return;
    if (localStorage.getItem('sh_singleton_web_app') === null) return;
    localStorage.removeItem('sh_singleton_web_app');
    diag('cleared stale "open in another tab" lock' + (isHidden(document) ? ' (background start)' : ''));
  });

  // ------------------------------------------------------------------ 2. Notification API
  var seq = 0;
  var lastNativeNotif = 0; // lần gần nhất Zalo tự tạo Notification
  /** Nội dung thông báo chung chung, không nói gì cụ thể: trống, "Bạn có (N) tin nhắn mới / chưa đọc"... */
  function generic(body) {
    var b = String(body == null ? '' : body).trim();
    return !b || /^(bạn\s+)?có\s+(\d+\+?\s+)?(tin nhắn|thông báo)\s+(mới|chưa đọc)\.?$/i.test(b) ||
      /^you have (\d+\+?\s+)?new (message|notification)s?\.?$/i.test(b);
  }
  var live = Object.create(null);
  var liveOrder = [];

  function absUrl(u) {
    if (!u) return '';
    try { return new URL(String(u), location.href).href; } catch (e) { return ''; }
  }

  function makeEvent(type, target) {
    return {
      type: type, target: target, currentTarget: target, srcElement: target,
      timeStamp: Date.now(), isTrusted: true, defaultPrevented: false,
      preventDefault: function () { this.defaultPrevented = true; },
      stopPropagation: function () {}, stopImmediatePropagation: function () {}
    };
  }

  function remember(n) {
    live[n._zwId] = n;
    liveOrder.push(n._zwId);
    while (liveOrder.length > 200) delete live[liveOrder.shift()];
  }

  function ZNotification(title, options) {
    if (!(this instanceof ZNotification)) {
      throw new TypeError("Failed to construct 'Notification': Please use the 'new' operator, " +
        'this DOM object constructor cannot be called as a function.');
    }
    if (arguments.length < 1) {
      throw new TypeError("Failed to construct 'Notification': 1 argument required, but only 0 present.");
    }
    options = options || {};
    var self = this;
    Object.defineProperty(this, '_zwId', { value: 'n' + Date.now().toString(36) + '_' + (++seq) });
    Object.defineProperty(this, '_zwListeners', { value: {} });
    this.title = String(title);
    this.body = options.body != null ? String(options.body) : '';
    this.tag = options.tag != null ? String(options.tag) : '';
    this.icon = options.icon ? absUrl(options.icon) : '';
    this.image = options.image ? absUrl(options.image) : '';
    this.badge = options.badge ? absUrl(options.badge) : '';
    this.data = options.data === undefined ? null : options.data;
    this.dir = options.dir || 'auto';
    this.lang = options.lang || '';
    this.silent = !!options.silent;
    this.renotify = !!options.renotify;
    this.requireInteraction = !!options.requireInteraction;
    this.timestamp = options.timestamp || Date.now();
    this.vibrate = [];
    this.actions = [];
    this.onclick = null;
    this.onshow = null;
    this.onclose = null;
    this.onerror = null;

    if (this.tag) {
      for (var k in live) { if (live[k].tag === this.tag) delete live[k]; }
    }
    remember(this);
    lastNativeNotif = Date.now();

    if (ZNotification.permission !== 'granted') {
      diag('zalo notification blocked: permission ' + ZNotification.permission);
      setTimeout(function () { self._zwFire('error'); }, 0);
      return;
    }
    // Zalo Web luôn đặt silent: true rồi tự phát tiếng "ting" trong trang (bị chặn khi ở nền, xem mục 5),
    // nên cờ này không có nghĩa là người dùng tắt tiếng: hội thoại đã tắt thông báo thì Zalo không gọi tới đây.
    diag('zalo notification' + (isHidden(document) ? '' : ' (page visible)'));
    if (generic(this.body)) { // không có nội dung cụ thể ("Bạn có tin nhắn mới"...): không hiện
      diag('zalo notification skipped: no content');
      setTimeout(function () { self._zwFire('show'); }, 0);
      return;
    }
    safe(function () {
      bridge.showNotification(self._zwId, self.title, zEmoji(self.body), self.tag, self.icon, false);
    });
    setTimeout(function () { self._zwFire('show'); }, 0);
  }

  ZNotification.prototype.addEventListener = function (type, fn) {
    if (!fn) return;
    var l = this._zwListeners[type] || (this._zwListeners[type] = []);
    if (l.indexOf(fn) < 0) l.push(fn);
  };
  ZNotification.prototype.removeEventListener = function (type, fn) {
    var l = this._zwListeners[type];
    if (!l) return;
    var i = l.indexOf(fn);
    if (i >= 0) l.splice(i, 1);
  };
  ZNotification.prototype.dispatchEvent = function (ev) {
    if (ev && ev.type) this._zwFire(ev.type, ev);
    return true;
  };
  Object.defineProperty(ZNotification.prototype, '_zwFire', {
    value: function (type, ev) {
      var self = this;
      var event = ev || makeEvent(type, self);
      var h = self['on' + type];
      if (typeof h === 'function') safe(function () { h.call(self, event); });
      (self._zwListeners[type] || []).slice().forEach(function (fn) {
        safe(function () {
          if (typeof fn === 'function') fn.call(self, event);
          else if (fn && typeof fn.handleEvent === 'function') fn.handleEvent(event);
        });
      });
    }
  });
  ZNotification.prototype.close = function () {
    var id = this._zwId;
    if (this._zwClosed) return;
    // Vẫn giữ trong live: Zalo tự đóng thông báo sau vài giây, còn thông báo Android thì ở lại (Notifier.cancel),
    // chạm vào sau đó vẫn phải chạy được onclick của Zalo để mở đúng hội thoại.
    Object.defineProperty(this, '_zwClosed', { value: true });
    var self = this;
    safe(function () { bridge.cancelNotification(id, self.tag); });
    setTimeout(function () { self._zwFire('close'); }, 0);
  };

  Object.defineProperty(ZNotification, 'permission', {
    enumerable: true, configurable: true,
    get: function () {
      var p = 'default';
      safe(function () { p = String(bridge.permission()); });
      return p;
    }
  });
  Object.defineProperty(ZNotification, 'maxActions', { value: 0, enumerable: true });
  Object.defineProperty(ZNotification, 'name', { value: 'Notification' });
  ZNotification.toString = function () { return 'function Notification() { [native code] }'; };

  var permCallbacks = Object.create(null);
  var permSeq = 0;
  ZNotification.requestPermission = function (cb) {
    return new Promise(function (resolve) {
      var done = function (state) {
        if (typeof cb === 'function') safe(function () { cb(state); });
        resolve(state);
      };
      var current = ZNotification.permission;
      if (current !== 'default') { done(current); return; }
      var id = 'p' + (++permSeq);
      permCallbacks[id] = done;
      safe(function () { bridge.requestPermission(id); });
    });
  };
  api.permResult = function (id, state) {
    var f = permCallbacks[id];
    if (f) { delete permCallbacks[id]; f(state); }
  };

  safe(function () {
    Object.defineProperty(window, 'Notification', { configurable: true, writable: true, value: ZNotification });
  });
  safe(function () {
    if (window.ServiceWorkerRegistration) {
      ServiceWorkerRegistration.prototype.showNotification = function (title, options) {
        new ZNotification(title, options);
        return Promise.resolve();
      };
      ServiceWorkerRegistration.prototype.getNotifications = function () { return Promise.resolve([]); };
    }
  });

  /** Người dùng chạm vào thông báo Android -> chạy onclick của Zalo (mở đúng cuộc trò chuyện). */
  api.click = function (id) {
    safe(function () { window.focus(); });
    var n = live[id];
    if (!n) return false;
    n._zwFire('click');
    // Giao diện điện thoại: chạm thông báo thì mở thẳng khung chat của hội thoại đó.
    if (api.mobileChat) setTimeout(function () { api.mobileChat(); }, 400);
    return true;
  };

  // ------------------------------------------------------------------ 3. Trông như Chrome trên Windows
  safe(function () {
    var nav = Navigator.prototype;
    Object.defineProperty(nav, 'platform', { configurable: true, enumerable: true, get: function () { return 'Win32'; } });
    var uad = Object.getOwnPropertyDescriptor(nav, 'userAgentData');
    if (uad && uad.get) {
      var origGet = uad.get;
      Object.defineProperty(nav, 'userAgentData', {
        configurable: true, enumerable: true,
        get: function () {
          var real = origGet.call(this);
          if (!real) return real;
          return {
            brands: real.brands,
            mobile: false,
            platform: 'Windows',
            getHighEntropyValues: function (hints) {
              return real.getHighEntropyValues(hints).then(function (v) {
                var o = Object.assign({}, v, { mobile: false, platform: 'Windows' });
                if ('platformVersion' in o) o.platformVersion = '10.0.0';
                if ('model' in o) o.model = '';
                if ('formFactors' in o) o.formFactors = ['Desktop'];
                return o;
              });
            },
            toJSON: function () { return { brands: real.brands, mobile: false, platform: 'Windows' }; }
          };
        }
      });
    }
  });

  // ------------------------------------------------------------------ 4. Micro/camera (cuộc gọi)
  var activeTracks = [];
  var mediaOn = false;
  var mediaVideo = false;

  function updateMedia() {
    activeTracks = activeTracks.filter(function (t) { return t.readyState === 'live'; });
    var on = activeTracks.length > 0;
    var video = activeTracks.some(function (t) { return t.kind === 'video'; });
    if (on !== mediaOn || video !== mediaVideo) {
      mediaOn = on;
      mediaVideo = video;
      if (on) stopRing();
      safe(function () { bridge.mediaActive(on, video); });
    }
  }

  function trackStream(stream) {
    safe(function () {
      stream.getTracks().forEach(function (t) {
        if (activeTracks.indexOf(t) < 0) activeTracks.push(t);
        t.addEventListener('ended', updateMedia);
      });
      updateMedia();
    });
    return stream;
  }

  safe(function () {
    if (window.MediaDevices && MediaDevices.prototype.getUserMedia) {
      var origGUM = MediaDevices.prototype.getUserMedia;
      MediaDevices.prototype.getUserMedia = function () {
        return origGUM.apply(this, arguments).then(trackStream);
      };
    }
    if (window.MediaStreamTrack) {
      var origStop = MediaStreamTrack.prototype.stop;
      MediaStreamTrack.prototype.stop = function () {
        var r = origStop.apply(this, arguments);
        setTimeout(updateMedia, 0);
        return r;
      };
    }
    setInterval(function () { if (mediaOn) updateMedia(); }, 5000);
  });

  // ------------------------------------------------------------------ 5. Đoán cuộc gọi đến
  /*
   * Không còn thông báo dự phòng chung chung ("Bạn có tin nhắn mới" theo số chưa đọc trên tiêu đề / tiếng ting): chỉ
   * hiện thông báo Zalo tự tạo, có tên người gửi và nội dung.
   */
  var ringingEl = null;
  function stopRing() {
    if (!ringingEl) return;
    ringingEl = null;
    safe(function () { bridge.ringing(false); });
  }

  safe(function () {
    var origPlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
      var el = this;
      // Tiếng "ting" ngắn khi đang chạy nền = có tin nhắn mới. Không phát: thông báo Android đã có chuông riêng.
      var ting = !el.loop && el.tagName === 'AUDIO' && isHidden(document) && !mediaOn;
      var result = ting ? Promise.resolve() : origPlay.apply(this, arguments);
      safe(function () {
        if (cfg.ringDetect !== false && el.loop && el.tagName === 'AUDIO' && isHidden(document) && !mediaOn && !ringingEl) {
          ringingEl = el;
          var stop = function () {
            el.removeEventListener('pause', stop);
            el.removeEventListener('ended', stop);
            el.removeEventListener('emptied', stop);
            if (ringingEl === el) stopRing();
          };
          el.addEventListener('pause', stop);
          el.addEventListener('ended', stop);
          el.addEventListener('emptied', stop);
          bridge.ringing(true);
        }
      });
      return result;
    };
  });

  // ------------------------------------------------------------------ 6. Tải tệp blob:/data:
  function saveFromUrl(url, name) {
    return fetch(url)
      .then(function (r) { return r.blob(); })
      .then(function (blob) {
        return new Promise(function (resolve, reject) {
          var fr = new FileReader();
          fr.onload = function () {
            var s = String(fr.result);
            var i = s.indexOf(',');
            bridge.saveFile(name || 'zalo-file', blob.type || 'application/octet-stream', i >= 0 ? s.slice(i + 1) : s);
            resolve();
          };
          fr.onerror = function () { reject(fr.error); };
          fr.readAsDataURL(blob);
        });
      })
      .catch(function (e) { log('download failed: ' + e); });
  }
  api.saveUrl = function (url, name) { saveFromUrl(url, name); };

  function handleAnchor(a) {
    if (!a || !a.hasAttribute || !a.hasAttribute('download')) return false;
    var href = a.href || '';
    if (href.indexOf('blob:') !== 0 && href.indexOf('data:') !== 0) return false;
    saveFromUrl(href, a.getAttribute('download') || 'zalo-file');
    return true;
  }

  safe(function () {
    var origClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      if (handleAnchor(this)) return undefined;
      return origClick.apply(this, arguments);
    };
    document.addEventListener('click', function (e) {
      var t = e.target;
      var a = t && t.closest ? t.closest('a[download]') : null;
      if (a && handleAnchor(a)) { e.preventDefault(); e.stopPropagation(); }
    }, true);
  });

  // ------------------------------------------------------------------ 7. Bề rộng trang
  var mobile = !!cfg.mobile;
  var vw = +cfg.viewportWidth || 0;
  if (mobile || vw > 0) safe(function () {
    var content = mobile
      ? 'width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no'
      : 'width=' + vw + ', user-scalable=yes';
    function fix(node) {
      if (!node || node.tagName !== 'META') return;
      if ((node.getAttribute('name') || '').toLowerCase() !== 'viewport') return;
      if (node.getAttribute('content') !== content) node.setAttribute('content', content);
    }
    function ensure() {
      var m = document.querySelector('meta[name="viewport"]');
      if (!m && document.head) {
        m = document.createElement('meta');
        m.setAttribute('name', 'viewport');
        document.head.appendChild(m);
      }
      fix(m);
    }
    var opts = { childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name'] };
    var mo = new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        var r = records[i];
        if (r.type === 'attributes') fix(r.target);
        else for (var j = 0; j < r.addedNodes.length; j++) fix(r.addedNodes[j]);
      }
    });
    mo.observe(document, opts);
    function onReady() {
      ensure();
      mo.disconnect();
      if (document.head) mo.observe(document.head, opts); // chỉ theo dõi <head> cho nhẹ
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', onReady);
    else onReady();
  });

  // ------------------------------------------------------------------ 8. Giao diện điện thoại
  /*
   * Bố cục thật của Zalo Web (đo trong app, 10/2026):
   *   - Bề rộng điện thoại: nav#sidebarNav chứa [cột biểu tượng #main-tab][danh sách chat]. Mở một hội thoại thì
   *     <main> (khung chat, có nút "<" .conv-back-btn) được chèn vào #container, đè lên danh sách; bấm "<" thì
   *     <main> bị gỡ khỏi trang.
   *   - Màn hình rộng hơn: #sidebarNav bên trái, bên phải là #chatOnboard (chưa mở hội thoại) hoặc <main>.
   *   - Tab Danh bạ: bên phải là .contact-pages (danh sách bạn bè, nhóm, lời mời), cũng có nút "<" .conv-back-btn.
   * Ở đây biến nó thành kiểu app điện thoại:
   *   - Màn hình chính: #sidebarNav chiếm hết bề rộng, ẩn #chatOnboard; thanh biểu tượng thành dock ở đáy.
   *   - Mở hội thoại (hoặc một trang danh bạ): phủ toàn màn hình, che cả dock.
   *   - Nút "<" của Zalo, nút ← của app (khi Zalo không có nút "<") hoặc phím Back của Android: về danh sách.
   */
  var M = { chat: false, main: null, ok: false, placeBack: null, cdetail: false, sync: null };

  function rectOf(el) { return el.getBoundingClientRect(); }
  function findMain() {
    return document.querySelector('#container > main, #container > span > main' +
      (M.cdetail ? ', #container > .contact-pages' : ''));
  }
  /** Trang chi tiết danh bạ đang mở (chạm một mục trong menu Danh bạ); tắt khi chuyển tab ở dock hoặc Back. */
  function setContactDetail(on) {
    M.cdetail = !!on;
    document.documentElement.classList.toggle('zd-cdetail', M.cdetail);
  }
  function zaloBack() { return M.main ? M.main.querySelector('.conv-back-btn') : null; }

  function setChat(on) {
    on = !!on;
    if (on && !(M.main && M.main.isConnected)) return;
    var changed = M.chat !== on;
    M.chat = on;
    document.documentElement.classList.toggle('zd-chat', on);
    if (changed) safe(function () { bridge.mobileState(on); });
    if (changed && !on && api.showDock) api.showDock(); // về danh sách: dock và thanh tìm kiếm luôn hiện
    if (M.placeBack) M.placeBack();
    if (api.syncDock) api.syncDock(); // dock native ẩn khi mở chat, hiện lại khi về danh sách
    if (on) setTimeout(function () { if (api.reportConv) api.reportConv(); }, 400); // chờ React dựng khung chat
  }

  /*
   * Hội thoại đang mở (mã như tag thông báo của Zalo — Zalo tạo Notification với tag = userId của hội thoại): báo app
   * xoá thông báo của riêng hội thoại đó. Mã lấy từ props "conversation" của khung chat (React).
   */
  var lastReported = '', lastReportedAt = 0;
  function openConvId() {
    var sc = document.getElementById('messageViewScroll');
    var fk = sc && Object.keys(sc).filter(function (k) { return k.indexOf('__react') === 0; })[0];
    for (var f = fk && sc[fk], i = 0; f && i < 40; f = f.return, i++) {
      var p = f.memoizedProps;
      if (p && p.conversation && p.conversation.userId) return String(p.conversation.userId);
      if (p && typeof p.conversationId === 'string') return p.conversationId;
    }
    return '';
  }
  api.reportConv = function () {
    safe(function () {
      if (!M.chat || isHidden(document)) return;
      var id = openConvId();
      if (!id || (id === lastReported && Date.now() - lastReportedAt < 1500)) return;
      lastReported = id;
      lastReportedAt = Date.now();
      bridge.convOpened(id);
    });
  };
  var leaving = false, passBack = false;
  api.mobileHome = function () {
    var m = M.main, b = zaloBack();
    function go() {
      setChat(false);
      if (m && m.classList.contains('contact-pages')) { // trang danh bạ: chỉ ẩn đi, về menu Danh bạ
        setContactDetail(false);
        if (M.sync) M.sync();
        return;
      }
      if (b) { // Zalo tự gỡ khung chat và trả danh sách về đúng trạng thái
        passBack = true;
        try { b.click(); } finally { passBack = false; }
      }
    }
    if (!mobile || !m || leaving) { go(); return true; }
    // Trượt khung ra trước (CSS .zd-leave) rồi mới quay lại danh sách.
    leaving = true;
    m.classList.add('zd-leave');
    setTimeout(function () { leaving = false; go(); m.classList.remove('zd-leave'); }, 190);
    return true;
  };
  api.mobileChat = function () { if (mobile) setChat(true); };
  /**
   * Nút Back của Android, lớp trên cùng đóng trước: xem ảnh → menu/popover → hộp thoại → khung chat.
   * Trả về true nếu đã xử lý; false thì app tự làm tiếp (đưa app về nền).
   */
  api.back = function () {
    return !!safe(function () {
      function vis(el) {
        if (!el || !el.isConnected) return false;
        var r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden';
      }
      function top(sel) {
        var list = [].filter.call(document.querySelectorAll(sel), vis);
        return list.length ? list[list.length - 1] : null;
      }
      function escape() {
        var t = document.activeElement || document.body;
        ['keydown', 'keyup'].forEach(function (type) {
          t.dispatchEvent(new KeyboardEvent(type, { key: 'Escape', code: 'Escape', keyCode: 27, which: 27,
            bubbles: true, cancelable: true }));
        });
      }
      var sm = document.getElementById('zd-share-menu');
      if (sm && sm.classList.contains('zd-on')) { sm.classList.remove('zd-on'); return true; }
      if (top('.zmenu-body, .context-menu, .popover-v3, .popover, .dropdown__list')) {
        // Menu của Zalo đóng khi bấm Esc hoặc bấm ra ngoài.
        escape();
        ['mousedown', 'mouseup'].forEach(function (type) {
          document.body.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
        });
        return true;
      }
      // Hộp thoại có thể mở chồng lên trình xem ảnh (chuyển tiếp ảnh): đóng hộp thoại trước.
      var modal = [].filter.call(document.querySelectorAll('.zl-modal, .modal-dialog'), function (m) {
        return vis(m) && !m.querySelector('.media-viewer'); // trình xem ảnh cũng nằm trong .zl-modal
      }).pop();
      if (modal) {
        // Hộp thoại nhiều trang (Cài đặt, Tạo nhóm...): đang ở trang con thì về trang trước bằng nút < của Zalo.
        var prev = [].filter.call(modal.querySelectorAll('[icon="icon-solid-left"]'), vis)[0];
        if (prev) { prev.click(); return true; }
        // Không lọc theo đang hiện: hộp thoại toàn màn hình ẩn nút X (đóng bằng Back), click() vẫn chạy trên nút ẩn.
        var close = modal.querySelector('.zl-modal__dialog__close, .zl-modal__dialog__header [icon^="close"],' +
          ' .zl-modal__dialog__header .fa-close, .modal-header .close');
        if (close) close.click(); else escape();
        return true;
      }
      var x = top('.media-viewer') && document.querySelector('.media-viewer #image-show__close'); // nút X đã ẩn
      if (x) { x.click(); return true; }
      if (api.closeInfo && api.closeInfo()) return true; // Thông tin hội thoại → về hội thoại
      // Đang tìm kiếm ở danh sách chat: thoát tìm kiếm (nút "Đóng" của Zalo).
      if (!M.chat && document.querySelector('#sidebarNav #recent-search-list, #sidebarNav #searchResultList')) {
        var done = [].filter.call(document.querySelectorAll('#contact-search .z--btn--v2'), function (b) {
          return /Đóng|Close/.test(b.textContent);
        })[0];
        if (done) { done.click(); return true; }
      }
      if (M.chat) return api.mobileHome();
      // Đang ở tab khác (Danh bạ): Back về tab Tin nhắn như app Zalo, chưa đưa app xuống nền.
      var msgTab = document.querySelector('#main-tab .leftbar-tab[data-translate-title=STR_TAB_MESSAGE]');
      if (M.ok && msgTab && !msgTab.classList.contains('selected') && document.querySelector('#main-tab .leftbar-tab.selected')) {
        msgTab.click();
        return true;
      }
      return false;
    });
  };
  api.mobileInfo = function () {
    return JSON.stringify({ enabled: mobile, detected: M.ok, chat: M.chat });
  };

  // Kẹp cấu trúc khung giao diện (không có nội dung tin nhắn / tên) để gửi cho người sửa lỗi.
  api.dumpLayout = function () {
    var out = [];
    function walk(el, depth) {
      if (depth > 16 || out.length > 800) return;
      var r = rectOf(el);
      if (r.width < 24 || r.height < 16) return;
      var cls = typeof el.className === 'string'
        ? el.className.trim().split(/\s+/).filter(Boolean).slice(0, 4) : [];
      var attrs = [];
      for (var i = 0; i < el.attributes.length; i++) {
        var n = el.attributes[i].name;
        if (n === 'data-zd') attrs.push('data-zd=' + el.attributes[i].value);
        else if (n.indexOf('data-') === 0 || n === 'role' || n === 'contenteditable') attrs.push(n);
      }
      out.push(new Array(depth + 1).join('  ') + el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') +
        (cls.length ? '.' + cls.join('.') : '') +
        ' [' + Math.round(r.left) + ',' + Math.round(r.top) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + ']' +
        (attrs.length ? ' {' + attrs.join(',') + '}' : ''));
      var kids = el.children;
      for (var k = 0; k < kids.length && k < 4; k++) walk(kids[k], depth + 1);
      if (kids.length > 4) out.push(new Array(depth + 2).join('  ') + '… +' + (kids.length - 4));
    }
    if (document.body) walk(document.body, 0);
    return 'viewport ' + innerWidth + 'x' + innerHeight + ' mobile=' + api.mobileInfo() + ' url=' +
      location.host + location.pathname + '\n' + out.join('\n');
  };

  if (mobile) safe(function () {
    var CSS = [
      'html.zd-m, html.zd-m body, html.zd-m #container { min-width: 0 !important; overflow-x: hidden !important; }',
      'html.zd-m #sidebarNav, html.zd-m #sidebarNav > :not(#main-tab) { flex: 1 1 auto !important;' +
        ' width: auto !important; max-width: none !important; min-width: 0 !important; }',
      'html.zd-m #chatOnboard { display: none !important; }',
      // Lời nhắc đồng bộ tin nhắn (banner đầu danh sách, nút cuối danh sách) và thẻ "Đồng bộ ngay / Tải Zalo PC
      // để xem đầy đủ tin nhắn" trong khung chat (nhận ra qua hình hai thiết bị chuyển tin).
      'html.zd-m .sync-v2-banner, html.zd-m .tds-conversation__footer-wrapper:has(.tds-conversation__footer-content-sync-button),' +
        ' html.zd-m .flx-centralized:has(> .tds-bubble-info-ecard__container img[src*="transfer-messages"])' +
        ' { display: none !important; }',
      // Banner hệ thống trên cùng "Sử dụng Zalo PC… Tải ngay" (nhận ra qua nút tải); banner khác vẫn hiện.
      // Gợi ý giới thiệu zCloud / trợ lý Kiki lúc mới đăng nhập (chỉ vào tab đã bỏ khỏi dock).
      'html.zd-m .zcloud-onboard-tip, html.zd-m .zcloud-offload-onboarding-tip,' +
        ' html.zd-m .kiki-assistant--onboarding-tip, html.zd-m .popover-v3:has(.zcloud-onboard-tip),' +
        ' html.zd-m .popover-v3:has(.zcloud-offload-onboarding-tip), html.zd-m .popover-v3:has(.kiki-assistant--onboarding-tip)' +
        ' { display: none !important; }',
      // Quảng cáo zCloud: banner đầu danh sách chat ("Bảo toàn dữ liệu… Tìm hiểu zCloud ngay"), banner hệ thống,
      // tooltip. Cảnh báo dung lượng (.cloud-banner) vẫn giữ.
      'html.zd-m [class^="conversation-list__banner"], html.zd-m [class*=" conversation-list__banner"],' +
        ' html.zd-m .list-chat-box-banner,' +
        ' html.zd-m .zcloud-banner-system, html.zd-m .zcloud-promo-tooltip, html.zd-m:not(.zd-zc-open) .zcloud-promo-content,' +
        ' html.zd-m .popover-v3:has(.zcloud-promo-tooltip-content) { display: none !important; }',
      // Khung chứa banner cao cố định 32px: chỉ có banner này thì ẩn cả khung cho khỏi chừa khoảng trắng.
      'html.zd-m .system-banner__item__wrapper:has([data-translate-inner="STR_NOTI_DOWNLOAD_BUTTON"]),' +
        ' html.zd-m .system-banner__container:has(> .system-banner__item__wrapper:only-child' +
        ' [data-translate-inner="STR_NOTI_DOWNLOAD_BUTTON"]) { display: none !important; }',
      // Thanh biểu tượng (#main-tab) thành dock ở đáy: Danh bạ, Tin nhắn, My Documents (avatar, Cài đặt lên thanh trên).
      // Chỉ đổi CSS, không di chuyển phần tử của Zalo; các lớp bọc thành display: contents để xếp lại bằng order.
      // Dock nổi: thanh bo tròn gọn ở giữa (3 nút, rộng 228px), cách đáy theo Cài đặt (--zd-dock-bottom), nội dung cuộn bên dưới. Nền kính mờ (data-zdg, xem updateDock):
      // màu dock của Zalo pha trong suốt theo Cài đặt, làm nhòe phần nằm sau, viền sáng mảnh như mặt kính.
      'html.zd-m #main-tab { position: fixed !important; left: 0 !important; right: 0 !important; margin: 0 auto !important;' +
        ' max-width: calc(100% - 24px); bottom: var(--zd-dock-bottom, 24px) !important;' +
        ' top: auto !important; width: var(--zd-dock-w, 228px) !important; height: 56px !important; padding: 0 2px !important;' +
        ' flex-direction: row !important; align-items: stretch !important; justify-content: space-around !important;' +
        ' border-radius: 28px !important; overflow: hidden !important; box-sizing: border-box !important;' +
        ' z-index: 12 !important; box-shadow: 0 6px 20px rgba(0,0,0,.25), inset 0 1px 0 rgba(255,255,255,.22),' +
        ' inset 0 0 0 1px rgba(255,255,255,.12); }',
      'html.zd-m #main-tab { transition: transform .42s cubic-bezier(.22,.61,.36,1) !important; }',
      'html.zd-m.zd-dockhide #main-tab { transform: translate3d(0, calc(100% + var(--zd-dock-bottom, 24px) + 12px), 0) !important; }',
      'html.zd-m #main-tab[data-zdg] { background: var(--zd-dock) !important;' +
        ' -webkit-backdrop-filter: blur(18px) saturate(1.8); backdrop-filter: blur(18px) saturate(1.8); }',
      // Vùng cuộn dưới dock: khoảng trống cuối danh sách (56px + khoảng cách đáy + 8px) để kéo được mục cuối lên trên dock.
      'html.zd-m [data-zdpad] { padding-bottom: calc(64px + var(--zd-dock-bottom, 24px)) !important; box-sizing: border-box !important; }',
      /*
       * Tự ẩn thanh tìm kiếm đầu danh sách (#contact-search, 64px) cùng lúc với dock, như Facebook: cột danh sách cao
       * hơn màn hình 64px và trượt lên 64px khi ẩn (chỉ transform — mượt, danh sách không phải đo lại). Thanh tab
       * Ưu tiên / Khác vẫn nằm trên cùng. Cuối danh sách chừa thêm 64px phần lòi ra dưới đáy khi thanh đang hiện.
       */
      'html.zd-m #sidebarNav > :not(#main-tab):has(> #contact-search) { height: calc(100% + 64px) !important;' +
        ' align-self: flex-start !important; transition: transform .42s cubic-bezier(.22,.61,.36,1); }',
      'html.zd-m.zd-dockhide:not(.zd-chat) #sidebarNav > :not(#main-tab):has(> #contact-search)' +
        ':not(:has(> #recent-search-list, > #searchResultList)) { transform: translate3d(0,-64px,0); }',
      // Đang tìm kiếm (Zalo chèn #recent-search-list / #searchResultList vào cột): ẩn dock, giữ thanh tìm kiếm luôn hiện.
      'html.zd-m:not(.zd-chat):has(#sidebarNav #recent-search-list, #sidebarNav #searchResultList) #main-tab' +
        ' { transform: translate3d(0, calc(100% + var(--zd-dock-bottom, 24px) + 12px), 0) !important; pointer-events: none; }',
      'html.zd-m #sidebarNav [data-zdpad] { padding-bottom: calc(128px + var(--zd-dock-bottom, 24px)) !important; }',
      'html.zd-m #main-tab > div, html.zd-m #main-tab .nav__tabs__top,' +
        ' html.zd-m #main-tab .nav__tabs__bottom > div:not(.leftbar-tab) { display: contents !important; }',
      'html.zd-m #main-tab .leftbar-tab, html.zd-m #main-tab .nav__tabs__zalo { display: flex !important;' +
        ' flex: 1 1 0 !important; flex-direction: column !important; align-items: center !important;' +
        ' justify-content: center !important; width: auto !important; height: 48px !important; min-width: 0 !important;' +
        ' margin: 4px 2px !important; padding: 0 !important; position: relative !important; z-index: 1;' +
        // Nền tab đang chọn: viên thuốc lùi vào 4px, bo 24px — đồng tâm với góc 28px của dock.
        ' border-radius: 24px !important; overflow: hidden !important; }',
      // Hiệu ứng chạm mặc định của WebView là ô chữ nhật (bỏ qua bo góc): tắt. Nút dock không có nền khi chạm (chỉ viên
      // thuốc #zd-ind trượt sang), nút trên thanh trên có nền mờ tròn.
      'html.zd-m #main-tab, html.zd-m #main-tab *, html.zd-m #contact-search, html.zd-m #contact-search *' +
        ' { -webkit-tap-highlight-color: transparent !important; }',
      // Không tô nền khi chạm / rê (trên cảm ứng :hover dính lại): nút thanh trên, thanh lọc, tab — nhìn tự nhiên hơn.
      'html.zd-m .msg-filters-bar, html.zd-m .msg-filters-bar * { -webkit-tap-highlight-color: transparent !important; }',
      'html.zd-m #contact-search .z--btn--v2, html.zd-m #contact-search .z--btn--v2:hover, html.zd-m #contact-search .z--btn--v2:active,' +
        ' html.zd-m .msg-filters-bar .z--btn--v2, html.zd-m .msg-filters-bar .z--btn--v2:hover, html.zd-m .msg-filters-bar .z--btn--v2:active,' +
        ' html.zd-m .msg-filters-bar .filter-preview-v2, html.zd-m .msg-filters-bar .filter-preview-v2:hover,' +
        ' html.zd-m .msg-filters-bar .filter-preview-v2:active, html.zd-m .msg-filters-bar .tab-item:hover,' +
        ' html.zd-m .msg-filters-bar .tab-item:active { background-color: transparent !important; }',
      'html.zd-m #main-tab .leftbar-tab, html.zd-m #main-tab .leftbar-tab:hover, html.zd-m #main-tab .leftbar-tab:active' +
        ' { background-color: transparent !important; }',
      // Nền tab đang chọn (.mmi-icon-wr, Zalo để 52x40 giữa nút): phủ kín cả nút như Telegram — viên thuốc rộng, cách
      // mép dock 4px, bo 24px đồng tâm với góc 28px của dock.
      'html.zd-m #main-tab .leftbar-tab .mmi-icon-wr { width: 100% !important; height: 100% !important;' +
        ' display: flex !important; align-items: center !important; justify-content: center !important;' +
        ' margin: 0 !important; border-radius: 24px !important; }',
      // Zalo vẽ nền sáng của tab đang chọn lên khung bọc icon bên trong (góc vuông): bo tròn cả các lớp bên trong.
      'html.zd-m #main-tab .leftbar-tab * { border-radius: 24px !important; }',
      // Thứ tự dock: Danh bạ – Tin nhắn (giữa) – My Documents.
      'html.zd-m #main-tab [data-translate-title=STR_TAB_CONTACT] { order: 1; }',
      'html.zd-m #main-tab [data-translate-title=STR_TAB_MESSAGE] { order: 2; }',
      'html.zd-m #main-tab .nav__tabs__zalo { order: 3; }',
      // Avatar chuyển lên thanh trên (#zd-head, xem syncHeader); phần tử gốc vẫn ở đây (ẩn) để mở menu tài khoản.
      'html.zd-m #main-tab .nav__tabs__zalo { display: none !important; }',
      // Cài đặt gộp vào avatar trên thanh (mở cùng menu): bỏ khỏi dock, dock còn Tin nhắn, Danh bạ, My Documents.
      'html.zd-m #main-tab [data-translate-title=STR_MENU_SETTING] { display: none !important; }',
      // Thanh trên: avatar + tên | nút tìm kiếm | thêm bạn | tạo nhóm.
      'html.zd-m #contact-search { display: flex !important; align-items: center !important; gap: 4px;' +
        ' padding: 0 8px 0 12px !important; }',
      '#zd-head { display: flex; align-items: center; gap: 10px; flex: 1 1 auto; min-width: 0; height: 44px;' +
        ' cursor: pointer; -webkit-tap-highlight-color: transparent; }',
      '#zd-head .zd-head-av { flex: 0 0 36px; width: 36px; height: 36px; border-radius: 50%; overflow: hidden; }',
      '#zd-head .zd-head-av .zavatar { width: 36px !important; height: 36px !important; min-width: 0 !important; }',
      '#zd-head .zd-head-name { min-width: 0; font-size: 17px; font-weight: 600; white-space: nowrap; overflow: hidden;' +
        ' text-overflow: ellipsis; color: var(--text-primary, inherit); }',
      'html.zd-searching #zd-head { display: none; }',
      // Tên + dòng trạng thái kết nối máy chủ Zalo ngay dưới: chấm đỏ mất kết nối, vàng (nhấp nháy) đang kết nối, xanh đã kết nối.
      '#zd-head .zd-head-txt { display: flex; flex-direction: column; justify-content: center; min-width: 0; gap: 1px; }',
      '#zd-head .zd-head-txt .zd-head-name { line-height: 21px; }',
      '#zd-head .zd-conn { --c: #30A46C; display: flex; align-items: center; gap: 5px; font-size: 12px; line-height: 16px;' +
        ' font-weight: 400; white-space: nowrap; color: var(--text-secondary, #5A6981); }',
      '#zd-head .zd-conn i { flex: 0 0 7px; width: 7px; height: 7px; border-radius: 50%; background: var(--c); transition: background-color .3s; }',
      '#zd-head .zd-conn[data-s=off] { --c: #E5484D; }',
      '#zd-head .zd-conn[data-s=wait] { --c: #F5A524; }',
      '#zd-head .zd-conn[data-s=wait] i { animation: zd-conn-pulse 1.1s ease-in-out infinite; }',
      // Dải cảnh báo mất mạng của Zalo trên đầu trang: trùng với nhãn kết nối, lại đẩy cả trang xuống.
      'html.zd-m .system-banner__item__wrapper:has(.z-banner.--warning) { display: none !important; }',
      '@keyframes zd-conn-pulse { 50% { opacity: .15; transform: scale(.7); } }',
      // Ô tìm kiếm khi không tìm: nút 32px giống hai nút thêm bạn / tạo nhóm bên cạnh (không nền, biểu tượng 20px);
      // ô nhập trong suốt phủ lên để chạm là focus.
      'html.zd-m:not(.zd-searching) #contact-search .group-search { flex: 0 0 32px !important; width: 32px !important;' +
        ' height: 32px !important; min-width: 0 !important; border-radius: 50% !important; overflow: hidden !important;' +
        ' margin: 0 !important; padding: 0 !important; }',
      'html.zd-m:not(.zd-searching) #contact-search .group-search .fake-textholder { display: none !important; }',
      'html.zd-m:not(.zd-searching) #contact-search .group-search input { width: 32px !important; height: 32px !important;' +
        ' padding: 0 !important; color: transparent !important; caret-color: transparent; background: transparent !important;' +
        ' border: 0 !important; box-shadow: none !important; }',
      'html.zd-m:not(.zd-searching) #contact-search .group-search:active { background: rgba(127,127,127,.18) !important; }',
      'html.zd-m:not(.zd-searching) #contact-search .group-search input::placeholder { color: transparent !important; }',
      'html.zd-m:not(.zd-searching) #contact-search .group-search .fa-search { left: 50% !important; top: 50% !important;' +
        // 14px -> ~17px: nét kính lúp đậm hơn hai biểu tượng bên cạnh, phóng đủ 20px trông to hơn hẳn.
        ' transform: translate(-50%, -50%) scale(1.2); margin: 0 !important; pointer-events: none; }',
      'html.zd-searching #contact-search .group-search { flex: 1 1 auto !important; }',
      // Menu tài khoản mở từ avatar trên thanh: đặt ngay dưới thanh, bên trái.
      '.popover-v3.zd-acct-pop { position: fixed !important; left: 8px !important; top: 60px !important;' +
        ' right: auto !important; bottom: auto !important; margin: 0 !important; transform: none !important; }',
      /*
       * Menu thao tác hội thoại (giữ lâu một dòng của danh sách native, mục 12) kiểu "focus" của app Zalo: nền phủ tối
       * (#zd-focus-scrim), dòng được giữ nổi lên thành thẻ bo góc (#zd-focus), menu thẳng mép trái thẻ, ngay dưới
       * (hoặc trên) thẻ. Thứ tự lớp: nền phủ < thẻ < menu, trên cả thanh trên và dock.
       */
      '.popover-v3.zd-conv-pop { position: fixed !important; left: 12px !important; right: auto !important;' +
        ' bottom: auto !important; margin: 0 !important; transform: none !important; max-width: calc(100vw - 24px);' +
        ' z-index: 9992 !important; }',
      '#zd-focus-scrim { position: fixed; inset: 0; z-index: 9990; background: rgba(0,0,0,.45); pointer-events: none;' +
        ' opacity: 0; transition: opacity .24s cubic-bezier(.2,.9,.25,1); }',
      '#zd-focus-scrim.on { opacity: 1; }',
      // Thẻ đứng yên tại chỗ dòng được giữ, chỉ "lún" nhẹ khi đang focus (như app Zalo) và trở lại khi bỏ focus.
      '#zd-focus { position: fixed; left: 0; width: 100vw; z-index: 9991; pointer-events: none; will-change: transform;' +
        ' transition: transform .2s cubic-bezier(.2,.9,.25,1), top .26s cubic-bezier(.2,.9,.25,1); }',
      '#zd-focus.on { transform: scale(.97); }',
      // Nền thẻ cố định, cách mép màn hình 4px (avatar cách mép 16px nên còn khoảng thở trong thẻ); chỉ hiện / mờ dần.
      '#zd-focus > .bg { position: absolute; top: 0; bottom: 0; left: 4px; right: 4px; border-radius: 16px;' +
        ' box-shadow: 0 6px 24px rgba(0,0,0,.18);' +
        ' background: linear-gradient(var(--zd-elev, transparent), var(--zd-elev, transparent)), var(--zd-listbg, #fff);' +
        ' opacity: 0; transition: opacity .18s cubic-bezier(.2,.9,.25,1); }',
      '#zd-focus.on > .bg { opacity: 1; }',
      '#zd-focus > img { position: absolute; left: 0; top: 0; width: 100%; height: 100%; }',
      // Ô emoji trạng thái đè lên ảnh đại diện: chạm xuyên qua để mở menu tài khoản.
      'html.zd-m #main-tab .zavatar__avatar-emoji-status { pointer-events: none !important; }',
      'html.zd-m #main-tab [data-translate-title=KEY_NAME_SEND2ME] { order: 4; }',
      'html.zd-m #main-tab [data-translate-title=STR_MENU_SETTING] { order: 5; }',
      // zCloud và Công cụ (cặp sách) sau My Documents, bên phải: hiện theo Cài đặt (zd-dk-cloud / zd-dk-tools, xem dockKeys).
      // Nút "…" gộp tab dùng chung data-id với nút Công cụ nên chọn Công cụ theo data-translate-title.
      'html.zd-m #main-tab [data-translate-title=STR_LEFT_SIDEBAR_ZALO_CLOUD] { order: 6; }',
      'html.zd-m #main-tab [data-translate-title=STR_TOOLS] { order: 7; }',
      // Popup của zCloud / Công cụ mở từ dock: toàn màn hình (placeDockPopover), nội dung ở giữa.
      'html.zd-m .popover-v3.zd-dock-full { position: fixed !important; inset: 0 !important; width: auto !important;' +
        ' height: auto !important; max-height: none !important; transform: none !important; border-radius: 0 !important;' +
        ' box-shadow: none !important; overflow: auto !important; z-index: 2147483000 !important; display: flex !important;' +
        ' flex-direction: column; align-items: center; justify-content: center; padding: 16px !important; box-sizing: border-box !important; }',
      'html.zd-m .popover-v3.zd-dock-full > * { flex: 0 0 auto !important; width: 100% !important; max-width: 440px !important; height: auto !important; }',
      'html.zd-m:not(.zd-dk-cloud) #main-tab .leftbar-tab[data-translate-title=STR_LEFT_SIDEBAR_ZALO_CLOUD],' +
        ' html.zd-m:not(.zd-dk-tools) #main-tab .leftbar-tab[data-translate-title=STR_TOOLS],' +
        ' html.zd-m #main-tab .leftbar-tab[data-id=div_Main_TabTool]:not([data-translate-title=STR_TOOLS]) { display: none !important; }',
      // Khung chat / trang danh bạ: phủ toàn màn hình. Chọn thẳng theo vị trí (không chờ shim gắn nhãn) để hiệu ứng
      // trượt vào chạy ngay khung hình đầu tiên Zalo chèn nó vào; chỉ animate transform để chạy trên luồng compositor,
      // vẫn mượt khi Zalo đang bận dựng tin nhắn.
      'html.zd-m #container > main, html.zd-m #container > span > main, html.zd-m #container > .contact-pages' +
        ' { position: fixed !important; top: 0 !important; left: 0 !important; right: 0 !important;' +
        ' bottom: 0 !important; width: auto !important; height: auto !important; min-width: 0 !important;' +
        ' max-width: none !important; margin: 0 !important; z-index: 15 !important;' + // trên dock (12), dưới hộp thoại của Zalo
        ' animation: zd-in .28s cubic-bezier(.2,.8,.2,1) both; box-shadow: -8px 0 24px rgba(0,0,0,.18); }',
      // Trang chi tiết danh bạ (bạn bè, nhóm, lời mời): Zalo luôn dựng sẵn khi ở tab Danh bạ. Tab Danh bạ mở ra menu
      // (danh sách mục bên trái); chạm một mục mới trượt trang chi tiết vào như mở hội thoại (zd-cdetail, xem M.cdetail).
      'html.zd-m:not(.zd-cdetail) #container > .contact-pages { display: none !important; }',
      // Quay lại: trượt ra trước, rồi mới để Zalo gỡ khung (api.mobileHome).
      'html.zd-m [data-zd=main].zd-leave { animation: zd-out .2s cubic-bezier(.4,0,1,1) both !important; }',
      // Màn hình rộng: khung chat vẫn nằm trong trang sau khi quay lại danh sách, chỉ ẩn đi.
      'html.zd-m:not(.zd-chat) [data-zd=main]:not(.zd-leave) { visibility: hidden; animation: none; }',
      '@keyframes zd-in { from { transform: translate3d(100%,0,0); } to { transform: translate3d(0,0,0); } }',
      '@keyframes zd-out { from { transform: translate3d(0,0,0); } to { transform: translate3d(100%,0,0); } }',
      // Chuyển tab ở dock: chỉ trang vừa hiện (zd-enter-*, gắn một lần trong slidePage) trượt vào theo hướng chuyển.
      // Không mờ về 0 (đỡ nháy nền trắng), không gắn lên thanh trên hay trang cũ.
      'html.zd-m .zd-enter-fwd { animation: zd-pg-fwd .3s cubic-bezier(.2,.8,.2,1); }',
      'html.zd-m .zd-enter-back { animation: zd-pg-back .3s cubic-bezier(.2,.8,.2,1); }',
      '@keyframes zd-pg-fwd { from { transform: translate3d(22%,0,0); opacity: .35; } to { transform: none; opacity: 1; } }',
      '@keyframes zd-pg-back { from { transform: translate3d(-22%,0,0); opacity: .35; } to { transform: none; opacity: 1; } }',
      /*
       * Viên thuốc tab đang chọn (#zd-ind, xem syncIndicator) trượt mượt sang nút mới (không nảy, không vượt khỏi dock); nền
       * đứng yên của Zalo (.mmi-icon-wr) bỏ đi. Màu theo nền dock (--zd-ind, updateDock).
       */
      '#zd-ind { position: absolute; top: 4px; left: 0; height: 48px; width: 0; border-radius: 24px; z-index: 0;' +
        ' pointer-events: none; background: var(--zd-ind, rgba(0,0,0,.15)); opacity: 0;' +
        ' transition: transform .35s cubic-bezier(.2,.8,.2,1), width .35s cubic-bezier(.2,.8,.2,1), opacity .2s; }',
      '#zd-ind.zd-on { opacity: 1; }',
      'html.zd-m #main-tab > #zd-ind { display: block !important; }', // các div con của dock đang là display: contents
      'html.zd-m #main-tab .leftbar-tab .mmi-icon-wr { background-color: transparent !important; }',
      // Dock native đang bật (NativeDock của app vẽ đè lên): dock của trang chỉ còn lớp làm nhòe nền (backdrop-filter).
      // Chỉ khi đã có data-zdg: updateDock bỏ data-zdg để đọc màu dock gốc của Zalo.
      'html.zd-m.zd-ndock #main-tab[data-zdg] { background: transparent !important;' +
        ' box-shadow: none !important; pointer-events: none !important; }',
      'html.zd-m.zd-ndock #main-tab * { visibility: hidden !important; }',
      // Danh sách native đang hiện: kính mờ nằm trong dock native; lớp nhòe của trang chỉ nhòe trang trong suốt, chồng
      // thêm một lớp lệch nhịp khi dock trượt — tắt.
      'html.zd-m.zd-ndock.zd-native #main-tab[data-zdg] { -webkit-backdrop-filter: none !important; backdrop-filter: none !important; }',
      '@keyframes zd-fade { from { opacity: 0; } to { opacity: 1; } }',
      'html.zd-m [data-zd=header] { padding-left: 44px !important; box-sizing: border-box !important; }',
      '#zd-back, #zd-info-back { display: none; position: fixed; left: 0; z-index: 16; width: 44px; margin: 0; padding: 0;' +
        ' border: 0; background: transparent; color: inherit; align-items: center; justify-content: center;' +
        ' -webkit-tap-highlight-color: transparent; cursor: pointer; }',
      '#zd-back.zd-on, #zd-info-back.zd-on { display: flex; }',
      '#zd-back:active, #zd-info-back:active { background: rgba(127,127,127,.2); }',
      // Khung chat không cuộn ngang: Zalo làm nội dung rộng hơn khung khi mở khung bên phải, trình duyệt cuộn lệch 32px.
      'html.zd-m [data-zd=main] { overflow: clip !important; }',
      // Khung bên phải (Thông tin hội thoại, Tìm kiếm trong trò chuyện — xem syncInfo): phủ cả khung chat, trượt vào
      // từ phải bằng hiệu ứng của app thay cho hiệu ứng kéo rộng của Zalo (hai cái chồng nhau trông như bung 2 lần).
      'html.zd-m [data-zd=main] > aside { position: fixed !important; top: 0 !important; left: 0 !important;' +
        ' right: 0 !important; bottom: 0 !important; width: auto !important; height: auto !important;' +
        ' min-width: 0 !important; max-width: none !important; margin: 0 !important; z-index: 20 !important;' +
        ' transition: none !important; animation: zd-in .28s cubic-bezier(.2,.8,.2,1) both !important; }',
      'html.zd-m [data-zd=main] > aside.zd-swap { animation: none !important; }',
      // Nền mờ phủ phần chat còn lại: không còn phần nào để phủ.
      'html.zd-m [data-zd=main] > aside > .cp-bg { display: none !important; }',
      // Nội dung khung giữ bề rộng cột 280px của Zalo: giãn hết khung.
      'html.zd-m [data-zd=main] > aside > :not(.cp-bg) { position: absolute !important; top: 0 !important;' +
        ' left: 0 !important; right: 0 !important; bottom: 0 !important; width: auto !important;' +
        ' max-width: none !important; border-left: 0 !important; }',
      // Thanh tiêu đề / thân khung Tìm kiếm có bề rộng cố định (343px): giãn theo khung.
      'html.zd-m [data-zd=main] > aside > :not(.cp-bg) > * { width: auto !important; max-width: none !important; }',
      'html.zd-m [data-zd=main] > aside { border-left: 0 !important; box-shadow: none !important; }',
      '#zd-info-back { z-index: 21; }',
      // Hộp thoại Tạo nhóm (#CREATE_GROUP_COMPOSE, rộng 520px), Thêm bạn (#FIND_FRIEND, 400px) và mọi hộp thoại
      // chọn người dùng chung bộ chọn của Tạo nhóm (.create-group__list — Thêm thành viên vào nhóm...): phủ toàn màn hình, trượt lên từ đáy thay cho hiệu ứng phóng to. Back / nút X vẫn đóng
      // như hộp thoại thường (api.back).
      'html.zd-m .zl-modal__container:has(> #CREATE_GROUP_COMPOSE, > #FIND_FRIEND, > #ADD_MEMBER_COMPOSE, > .zl-modal__dialog .create-group__list) { padding: 0 !important; }',
      'html.zd-m #CREATE_GROUP_COMPOSE, html.zd-m #FIND_FRIEND, html.zd-m #ADD_MEMBER_COMPOSE,' +
        ' html.zd-m .zl-modal__dialog:has(.create-group__list) { width: 100% !important; height: 100% !important;' +
        ' max-width: none !important; max-height: none !important; margin: 0 !important; border: 0 !important;' +
        ' border-radius: 0 !important; align-self: stretch !important;' +
        // Bàn phím bật/tắt: Zalo căn lại hộp thoại bằng top: Npx (Thêm bạn) — đẩy nó xuống, nút đáy lọt khỏi màn hình.
        ' position: relative !important; top: 0 !important; left: 0 !important;' +
        ' animation: zd-up .26s cubic-bezier(.2,.8,.2,1) both !important; }',
      // Cài đặt của Zalo (hộp thoại chứa #setting, rộng 406px, cao 100vh - 32px): phủ toàn màn hình như trên, không có
      // nút X; Back ở trang con (Giao diện, Thông báo...) về danh sách mục, ở danh sách thì đóng (api.back).
      'html.zd-m .zl-modal__container:has(> .zl-modal__dialog #setting) { padding: 0 !important; }',
      'html.zd-m .zl-modal__dialog:has(#setting) { width: 100% !important; height: 100% !important; max-width: none !important;' +
        ' max-height: none !important; margin: 0 !important; border: 0 !important; border-radius: 0 !important;' +
        ' align-self: stretch !important; position: relative !important; top: 0 !important; left: 0 !important;' +
        ' animation: zd-up .26s cubic-bezier(.2,.8,.2,1) both !important; }',
      'html.zd-m .zl-modal__dialog:has(#setting) > .zl-modal__dialog-body { flex: 1 1 auto !important; height: auto !important;' +
        ' min-height: 0 !important; max-height: none !important; }',
      'html.zd-m .zl-modal__dialog #setting { height: 100% !important; max-height: none !important; }',
      /*
       * Hộp thoại Tạo nhắc hẹn (.reminder-settings), Tạo bình chọn (.group-poll-create[-v3]), Tạo ghi chú
       * (.create-topic-v2): dạng thân + chân nút. Phủ toàn màn hình, thân cuộn được, nút ở đáy; không có nút X (Back đóng).
       */
      'html.zd-m .zl-modal__container:has(> .zl-modal__dialog :is(.reminder-settings, .group-poll-create, .group-poll-create-v3,' +
        ' .create-topic-v2)) { padding: 0 !important; }',
      'html.zd-m .zl-modal__dialog:has(.reminder-settings, .group-poll-create, .group-poll-create-v3, .create-topic-v2) {' +
        ' width: 100% !important; height: 100% !important; max-width: none !important; max-height: none !important;' +
        ' margin: 0 !important; border: 0 !important; border-radius: 0 !important; align-self: stretch !important;' +
        ' position: relative !important; top: 0 !important; left: 0 !important;' +
        ' animation: zd-up .26s cubic-bezier(.2,.8,.2,1) both !important; }',
      'html.zd-m .zl-modal__dialog:has(.reminder-settings, .group-poll-create, .group-poll-create-v3, .create-topic-v2)' +
        ' > .zl-modal__dialog-body { flex: 1 1 auto !important; height: auto !important; min-height: 0 !important;' +
        ' max-height: none !important; overflow-y: auto !important; }',
      'html.zd-m .zl-modal__dialog:has(.reminder-settings, .group-poll-create, .group-poll-create-v3, .create-topic-v2)' +
        ' .zl-modal__dialog__header [icon^="close"] { display: none !important; }',
      // Nút chọn nhanh giờ nhắc (15 phút nữa, 9:00 ngày mai...): giữ chữ một dòng, hết chỗ thì xuống hàng cả nút.
      'html.zd-m .reminder-time-container { flex-wrap: wrap !important; height: auto !important; gap: 8px; }',
      'html.zd-m .reminder-time-container .chips { height: 32px !important; white-space: nowrap !important; margin: 0 !important; }',
      'html.zd-m .reminder-time-container .chips > div { height: auto !important; white-space: nowrap !important; }',
      /*
       * Danh sách chat native (mục 12, html.zd-native): danh sách web ẩn đi (vẫn chạy để Zalo cập nhật), nền từ trang
       * xuống tới cột danh sách trong suốt để thấy danh sách native vẽ dưới WebView; thanh trên giữ màu nền danh sách.
       * Thanh lọc của Zalo (Ưu tiên / Khác hoặc Tất cả / Chưa đọc, Phân loại, …) giữ nguyên trên danh sách native:
       * chọn tab nào thì danh sách native lấy đúng các hội thoại web đang lọc (mục 12, webOrder).
       */
      'html.zd-m.zd-native, html.zd-m.zd-native body, html.zd-m.zd-native :has(#sidebarNav),' +
        ' html.zd-m.zd-native #sidebarNav, html.zd-m.zd-native #sidebarNav > :not(#main-tab),' +
        ' html.zd-m.zd-native #sidebarNav > :not(#main-tab) > :not(#contact-search) { background: transparent !important; }',
      // Ẩn bằng opacity phần còn lại dưới thanh lọc: visibility: hidden thì phần tử con nào Zalo đặt visibility:
      // visible (biểu tượng ghim, khung tải...) vẫn hiện, kèm nền xám che danh sách native.
      'html.zd-m.zd-native #sidebarNav > :not(#main-tab) > :not(#contact-search) > :not(.msg-filters-bar)' +
        ' { opacity: 0 !important; pointer-events: none !important; }',
      // Tab lọc trống: hiện màn trống của Zalo ("Không có tin nhắn chưa đọc"...) thay cho danh sách native rỗng.
      'html.zd-m.zd-native.zd-nempty #sidebarNav > :not(#main-tab) > :not(#contact-search) > :not(.msg-filters-bar)' +
        ' { opacity: 1 !important; }',
      // Thanh trên + thanh lọc do app vẽ (NativeChatList.Header): của trang ẩn đi nhưng vẫn ở đúng chỗ — app bấm hộ nút
      // của nó (api.headTap) và menu của Zalo mở ra đúng vị trí nút.
      'html.zd-m.zd-native #contact-search, html.zd-m.zd-native #sidebarNav .msg-filters-bar' +
        ' { background: transparent !important; opacity: 0 !important; }',
      // Nút "Chụp kèm với cửa sổ Zalo" (chụp màn hình máy tính): không dùng được trên điện thoại.
      'html.zd-m [data-translate-title^="STR_SCREENSHOT_WITH_ZALO"] { display: none !important; }',
      /*
       * Bảng sticker mở rộng (nút ⤢): Zalo đưa vào khung bên phải (.media-dock, tab Thông tin | Biểu tượng) với phần
       * sticker (.media-dock-tabs, chỉ có khi đang ở tab Biểu tượng) rộng cố định đè lên nội dung Thông tin. Cho phần
       * sticker giãn hết bề ngang và ẩn nội dung Thông tin phía sau.
       */
      'html.zd-m .media-dock .media-dock-tabs { left: 0 !important; right: 0 !important; width: auto !important;' +
        ' max-width: none !important; }',
      'html.zd-m .media-dock:has(.media-dock-tabs) .media-dock-contents { visibility: hidden !important; }',
      'html.zd-m .media-dock .media-dock-tabs .sticker-selector, html.zd-m .media-dock .media-dock-tabs .tab-main' +
        ' { width: 100% !important; max-width: none !important; }',
      /*
       * Hộp thoại Biểu cảm (ai thả biểu cảm gì): cột tab bên trái (.reaction-popup-tab, 120px: Tất cả, từng biểu cảm)
       * thành hàng tab ngang ở trên, cuộn ngang; danh sách người thả chiếm hết bề ngang bên dưới. Cột tab có thể là
       * danh sách ảo hoá (các hàng đặt tuyệt đối theo top) nên ép các hàng về xếp ngang.
       */
      'html.zd-m :has(> .reaction-popup-tab) { flex-direction: column !important; }',
      'html.zd-m .reaction-popup-tab { width: auto !important; height: 48px !important; flex: 0 0 48px !important;' +
        ' overflow-x: auto !important; overflow-y: hidden !important; scrollbar-width: none; }',
      'html.zd-m .reaction-popup-tab ::-webkit-scrollbar { display: none; }',
      'html.zd-m .reaction-popup-tab :is(.ReactVirtualized__Grid, .ReactVirtualized__List, .reaction-popup-tab-list) {' +
        ' width: 100% !important; height: 48px !important; max-height: none !important; padding: 0 !important;' +
        ' overflow-x: auto !important; overflow-y: hidden !important; }',
      'html.zd-m .reaction-popup-tab :is(.ReactVirtualized__Grid__innerScrollContainer, .reaction-popup-tab-list) {' +
        ' display: flex !important; flex-direction: row !important; width: max-content !important; min-width: 100%;' +
        ' height: 48px !important; max-width: none !important; max-height: none !important; overflow: visible !important; }',
      'html.zd-m .reaction-popup-tab .ReactVirtualized__Grid__innerScrollContainer > * { position: relative !important;' +
        ' top: auto !important; left: auto !important; width: auto !important; height: 48px !important; flex: 0 0 auto !important; }',
      'html.zd-m .reaction-popup-tab-item { gap: 8px; height: 48px !important; padding: 0 16px !important; box-sizing: border-box; }',
      'html.zd-m .reaction-popup-tab-item.active { background: transparent !important;' +
        ' box-shadow: inset 0 -2px 0 var(--border-focused, #0068ff); }',
      'html.zd-m .reaction-popup-list { width: 100% !important; min-height: 0; }',
      'html.zd-m .zl-modal__dialog #setting-right { position: relative !important; }', // khung cho .stack-navigation bám
      'html.zd-m .zl-modal__dialog:has(#setting) [icon^="close"] { display: none !important; }',
      // Màn hình toàn màn hình đóng bằng Back (và nút Hủy): bỏ nút X ở tiêu đề hộp thoại và khung Tìm kiếm trong trò
      // chuyện (khung này đã có nút ← của app). Back vẫn bấm nút ẩn này (api.back, api.closeInfo).
      'html.zd-m :is(#CREATE_GROUP_COMPOSE, #FIND_FRIEND, #ADD_MEMBER_COMPOSE, .zl-modal__dialog:has(.create-group__list)) .zl-modal__dialog__header [icon^="close"],' +
        ' html.zd-m [data-zd=main] > aside .search-message-inchat__header__right-btn { display: none !important; }',
      // Nội dung (.stack-navigation) được Zalo gán chiều cao cố định đo lúc mở — đo khi bàn phím đang bật thì đóng bàn
      // phím xong để hở một khoảng dưới đáy. Cho thân hộp thoại giãn hết phần còn lại và nội dung bám theo nó.
      'html.zd-m :is(#CREATE_GROUP_COMPOSE, #FIND_FRIEND, #ADD_MEMBER_COMPOSE, .zl-modal__dialog:has(.create-group__list)) > .zl-modal__dialog-body { flex: 1 1 auto !important;' +
        ' height: auto !important; min-height: 0 !important; max-height: none !important; }',
      'html.zd-m :is(#CREATE_GROUP_COMPOSE, #FIND_FRIEND, #ADD_MEMBER_COMPOSE, .zl-modal__dialog:has(.create-group__list)) > .zl-modal__dialog-body > div { position: relative !important; }',
      'html.zd-m :is(#CREATE_GROUP_COMPOSE, #FIND_FRIEND, #ADD_MEMBER_COMPOSE, .zl-modal__dialog:has(.create-group__list), #setting-right) .stack-navigation { position: absolute !important; top: 0 !important;' +
        ' left: 0 !important; right: 0 !important; bottom: 0 !important; width: auto !important; height: auto !important;' +
        ' min-height: 0 !important; max-height: none !important; }',
      // Trang trong: [vùng cuộn có chiều cao cố định][thanh nút .pi-footer-section (Thêm bạn)] — vùng cuộn giãn, nút ở đáy.
      'html.zd-m :is(#CREATE_GROUP_COMPOSE, #FIND_FRIEND, #ADD_MEMBER_COMPOSE, .zl-modal__dialog:has(.create-group__list), #setting-right) .stack-page { height: 100% !important; display: flex !important;' +
        ' flex-direction: column !important; }',
      'html.zd-m :is(#CREATE_GROUP_COMPOSE, #FIND_FRIEND, #ADD_MEMBER_COMPOSE, .zl-modal__dialog:has(.create-group__list), #setting-right) .stack-page > div { flex: 1 1 auto !important; height: auto !important;' +
        ' min-height: 0 !important; max-height: none !important; }',
      'html.zd-m :is(#CREATE_GROUP_COMPOSE, #FIND_FRIEND, #ADD_MEMBER_COMPOSE, .zl-modal__dialog:has(.create-group__list), #setting-right) .stack-page > .pi-footer-section { flex: 0 0 auto !important;' +
        ' padding-bottom: 16px !important; }',
      '@keyframes zd-up { from { transform: translate3d(0,100%,0); } to { transform: translate3d(0,0,0); } }',
      /*
       * Xem ảnh như Zalo trên điện thoại: ảnh tràn màn hình; thanh trên mờ dần có nút đóng, người gửi + giờ, tải về,
       * xoay; nút chuyển tiếp tròn ở góc dưới phải. Bỏ cột ảnh nhỏ, nút lên/xuống, phóng to/thu nhỏ, thả cảm xúc.
       * Cử chỉ ở đoạn mã bên dưới (vuốt chuyển ảnh, 2 ngón phóng to, chạm ẩn/hiện thanh).
       */
      'html.zd-m .media-viewer { position: relative; }',
      'html.zd-m .media-viewer__body { position: absolute !important; top: 0; left: 0; right: 0; bottom: 0;' +
        ' height: auto !important; background: #000 !important; }',
      'html.zd-m .media-viewer__body__left { width: 100% !important; box-sizing: border-box; padding: 0 !important; }',
      'html.zd-m .media-viewer__body__left .navigatorWrapper,' +
        ' html.zd-m .image-show__title .fl-l, html.zd-m .media-viewer__footer__child.other,' +
        ' html.zd-m .media-viewer__footer .image-action > action-group:nth-of-type(3) { display: none !important; }',
      'html.zd-m .media-viewer__header { position: absolute !important; top: 0; left: 0; right: 0;' +
        ' height: 56px !important; z-index: 31; background: linear-gradient(rgba(0,0,0,.6), rgba(0,0,0,0)) !important;' +
        ' transition: opacity .2s; }',
      'html.zd-m .media-viewer__title-bar, html.zd-m .image-show__title { height: 56px !important;' +
        ' background: transparent !important; }',
      // Không có nút X: đóng bằng Back hoặc vuốt (api.back vẫn bấm nút ẩn này).
      'html.zd-m #image-show__close { display: none !important; }',
      // Nhãn "Người lạ" ở thanh trên khung chat: không co, không xuống dòng (cỡ chữ lớn bị gãy 2 dòng).
      'html.zd-m .threadChat__btn-is-friend { flex-shrink: 0 !important; white-space: nowrap !important; }',
      'html.zd-m .threadChat__mutual-include { min-width: 0 !important; overflow: hidden; }',
      // Số nhóm chung ("N nhóm chung" / "Không có nhóm chung"): bỏ hẳn cho thanh trên gọn, cùng vạch ngăn sau nó; không
      // phải người lạ thì cả khối trống, ẩn luôn.
      'html.zd-m .threadChat__mutual-group, html.zd-m .threadChat__mutual-include + .new-separator,' +
        ' html.zd-m .threadChat__mutual-include:not(:has(.threadChat__btn-is-friend)) { display: none !important; }',
      'html.zd-m .media-viewer__footer { position: absolute !important; top: 0; left: 0; right: 0;' +
        ' width: auto !important; height: 56px !important; z-index: 32; background: transparent !important;' +
        ' pointer-events: none; transition: opacity .2s; }',
      'html.zd-m .media-viewer__footer > * { pointer-events: auto; }',
      'html.zd-m .media-viewer__footer__child.sender { position: absolute; left: 12px; top: 0; height: 56px;' +
        ' max-width: calc(100% - 152px); color: #fff; }',
      'html.zd-m .media-viewer__footer__child.sender sender-info { width: auto !important; min-width: 0; }',
      'html.zd-m .media-viewer__footer__child.sender sender-name { max-width: 100%; color: #fff; }',
      // Tải về, xoay: dời sang trái chừa chỗ nút chia sẻ #zd-share ở góc phải, cả ba cùng một hàng.
      'html.zd-m .media-viewer__footer .image-action { position: absolute; right: 48px; top: 0; height: 56px; width: auto !important; }',
      'html.zd-m .media-viewer__footer .image-action action-group { border: 0 !important; }',
      'html.zd-m .media-viewer__footer .image-action i { color: #fff; }',
      // Nút chuyển tiếp của Zalo ẩn đi: gộp vào nút chia sẻ #zd-share (menu: chuyển tiếp trong Zalo / app khác).
      'html.zd-m .media-viewer__footer .btn-forward { display: none !important; }',
      /*
       * Cột ảnh nhỏ bên phải của Zalo (.media-viewer__body__right > .sliderView, mỗi ảnh .img-sh-th 88px) thành dải
       * ngang ở đáy, cuộn ngang; ảnh đang xem viền trắng, luôn được cuộn vào giữa (scrollThumbStrip). Nhãn ngày ẩn.
       */
      'html.zd-m .media-viewer__body__right { display: block !important; position: absolute !important; left: 0;' +
        ' right: 0; bottom: 0; top: auto !important; width: auto !important; height: 76px !important; z-index: 31;' +
        ' padding: 0 !important; margin: 0 !important; border: 0 !important; background: linear-gradient(rgba(0,0,0,0), rgba(0,0,0,.6)) !important;' +
        ' transition: opacity .2s; }',
      'html.zd-m .media-viewer__body__right .sliderView { flex-direction: row !important; align-items: center !important;' +
        ' gap: 6px; width: 100% !important; height: 100% !important; padding: 0 12px !important; box-sizing: border-box;' +
        ' position: relative !important;' + // offsetParent của ảnh nhỏ: offsetLeft tính trong dải (scrollThumbStrip)
        ' overflow-x: auto !important; overflow-y: hidden !important; scrollbar-width: none; }',
      'html.zd-m .media-viewer__body__right .sliderView::-webkit-scrollbar { display: none; }',
      'html.zd-m .media-viewer__body__right .sliderTime { display: none !important; }',
      'html.zd-m .media-viewer__body__right .img-sh-th { flex: 0 0 auto !important; width: 56px !important; height: 56px !important;' +
        ' margin: 0 !important; border-radius: 8px; overflow: hidden; box-sizing: border-box; opacity: .65;' +
        ' border: 2px solid transparent !important; }',
      'html.zd-m .media-viewer__body__right .img-sh-th.-selected { opacity: 1; border-color: #fff !important; }',
      'html.zd-m .media-viewer__body__right .img-sh-th .img-center-box { width: 100% !important; height: 100% !important; }',
      'html.zd-m .media-viewer__body__right .img-sh-th .btn-play { transform: scale(.7); }',
      'html.zd-m .media-viewer.zd-bare .media-viewer__body__right { opacity: 0; pointer-events: none; }',
      // Chú thích ảnh: trải hết bề ngang dưới đáy (chừa chỗ các nút tròn bên phải), thay vì khung hẹp 60%.
      'html.zd-m .mvr-caption { left: 0 !important; right: 0 !important; bottom: 76px !important; width: auto !important;' +
        ' max-width: none !important; margin: 0 !important; transform: none !important; border-radius: 0 !important;' +
        ' box-sizing: border-box;' +
        ' padding: 12px 16px 24px 16px !important; background: linear-gradient(rgba(0,0,0,0), rgba(0,0,0,.7) 35%) !important;' +
        ' transition: opacity .2s; }',
      'html.zd-m .mvr-caption .scrollhost-container, html.zd-m .mvr-caption .scrollhost,' +
        ' html.zd-m .mvr-caption p { width: auto !important; max-width: none !important; }',
      'html.zd-m .media-viewer.zd-bare .media-viewer__header, html.zd-m .media-viewer.zd-bare .media-viewer__footer,' +
        ' html.zd-m .media-viewer.zd-bare .mvr-caption { opacity: 0; }',
      'html.zd-m .media-viewer.zd-bare .media-viewer__header *, html.zd-m .media-viewer.zd-bare .media-viewer__footer *' +
        ' { pointer-events: none !important; }',
      /*
       * Trang đăng nhập (mã QR): thẻ của Zalo rộng cố định 540px và có quảng cáo Zalo PC, tràn ngang và phải cuộn.
       * Thu về vừa một màn hình điện thoại: thẻ co theo bề ngang, mã QR ở giữa, bỏ quảng cáo.
       */
      'html.zd-m .zLogin-layout { display: flex; flex-direction: column; justify-content: center; gap: 16px;' +
        ' min-height: 100dvh; box-sizing: border-box; padding: 16px 0; }',
      'html.zd-m .zLogin-layout .header, html.zd-m .zLogin-layout .body, html.zd-m .zLogin-layout .footer' +
        ' { margin: 0 !important; padding: 0 !important; height: auto !important; min-height: 0 !important; }',
      'html.zd-m .zLogin-layout .header h1 { margin: 0 0 8px !important; }',
      'html.zd-m .zLogin-layout .header h2 { margin: 0 !important; padding: 0 16px; font-size: 15px !important;' +
        ' line-height: 1.4 !important; }',
      'html.zd-m .zLogin-layout .zcard.body-container { width: auto !important; max-width: none !important;' +
        ' min-width: 0 !important; margin: 0 16px !important; }',
      'html.zd-m .zLogin-layout .zcard-head-title, html.zd-m .zLogin-layout .zcard-head-title p' +
        ' { width: auto !important; text-align: center; }',
      'html.zd-m .zLogin-layout .zcard-body { padding: 12px !important; }',
      'html.zd-m .zLogin-layout .zcard-body > :not(.form-signin) { display: none !important; }', // quảng cáo Zalo PC
      'html.zd-m .zLogin-layout .form-signin { width: auto !important; margin: 0 !important; padding: 0 !important; }',
      'html.zd-m .zLogin-layout .form-signin * { max-width: 100%; white-space: normal !important; }',
      'html.zd-m .zLogin-layout .in-tableCell { display: block !important; width: auto !important; padding: 0 !important; }',
      'html.zd-m .zLogin-layout .qrcode { display: flex; flex-direction: column; align-items: center;' +
        ' width: auto !important; text-align: center; }',
      'html.zd-m .zLogin-layout .footer .select-lang { margin: 0 !important; }',
      // Dòng "Chỉ dùng để đăng nhập / Zalo trên máy tính" dưới mã QR: không đúng với app này.
      'html.zd-m .zLogin-layout .qr-guide { display: none !important; }',
      // Nút chia sẻ ở góc phải thanh trên, cạnh tải về / xoay (chỉ khi đang xem ảnh); bấm vào hiện menu #zd-share-menu.
      '#zd-share { display: none; position: fixed; right: 4px; top: 4px; z-index: 2147483000; width: 48px; height: 48px;' +
        ' border: 0; border-radius: 50%; padding: 0; background: transparent; color: #fff; align-items: center;' +
        ' justify-content: center; -webkit-tap-highlight-color: transparent; transition: opacity .2s; }',
      'html.zd-m:has(.media-viewer) #zd-share { display: flex; }',
      // Thanh điều khiển video (.zvideo-player-controls-container, rộng 100% khung video — khung có thể rộng hơn màn
      // hình nên tràn hai bên): bám theo màn hình, cách hai bên 12px, ngay trên dải ảnh nhỏ (cách đáy 84px). Lớp cha có transform nên fixed tính theo lớp cha: placeVideoControls bù độ lệch bằng left/right/bottom.
      'html.zd-m .media-viewer .zvideo-player-controls-container { position: fixed !important; width: auto !important; }',
      'html.zd-m .media-viewer .zvideo-player-controls { width: 100% !important; border-radius: 20px !important;' +
        ' padding: 8px 12px !important; }',
      // Thanh hẹp lại thì giờ chạy bị co ("00:0"): giữ đủ chữ, thanh tiến trình co thay.
      'html.zd-m .media-viewer .zvideo-player-time { flex: 0 0 auto !important; width: auto !important; overflow: visible !important; }',
      'html.zd-m .media-viewer .zvideo-player-time-slash { position: static !important; transform: none !important; margin: 0 2px; }',
      'html.zd-m .media-viewer .zvideo-player-progress-bar { flex: 1 1 auto !important; min-width: 0 !important; margin-left: 8px; }',
      'html.zd-m:has(.media-viewer.zd-bare) #zd-share { opacity: 0; pointer-events: none; }',
      // Hộp thoại của Zalo (chuyển tiếp...) mở chồng lên; trình xem ảnh cũng là .zl-modal nhưng không có header.
      'html.zd-m:has(.zl-modal__dialog__header) #zd-share { display: none; }',
      '#zd-share:active { background: rgba(255,255,255,.2); }',
      '#zd-share-menu { display: none; position: fixed; inset: 0; z-index: 2147483001; background: rgba(0,0,0,.35);' +
        ' -webkit-tap-highlight-color: transparent; }',
      '#zd-share-menu.zd-on { display: block; }',
      '#zd-share-menu > div { position: absolute; right: 8px; top: 56px; min-width: 240px; padding: 6px 0;' +
        ' border-radius: 14px; background: #2b2d30; color: #fff; box-shadow: 0 6px 24px rgba(0,0,0,.5);' +
        ' animation: zd-fade .15s ease-out; }',
      '#zd-share-menu button { display: flex; align-items: center; gap: 14px; width: 100%; padding: 14px 18px;' +
        ' border: 0; background: transparent; color: inherit; font: inherit; font-size: 15px; text-align: left; }',
      '#zd-share-menu button:active { background: rgba(255,255,255,.1); }',
      'html.zd-m .media-viewer__body__left .mediaViewerMain { touch-action: none; }',
      'html.zd-m .media-viewer-main-view { transition: transform .15s ease-out; will-change: transform; }',
      'html.zd-m .media-viewer-main-view.zd-touching { transition: none; }',
      // Ảnh kế bên khi vuốt chuyển ảnh (ảnh nhỏ của dải ảnh, đã tải sẵn): nằm sát khung ảnh, đi theo ngón tay.
      'html.zd-m .media-viewer__body__left { position: relative; }',
      '.zd-swipe-ghost { position: absolute; z-index: 5; pointer-events: none; overflow: hidden; background: #000;' +
        ' will-change: transform, opacity; }',
      '.zd-swipe-ghost img { width: 100%; height: 100%; object-fit: contain; display: block; }'
    ].join('\n');

    /*
     * Cử chỉ trên ảnh (WebView tắt phóng to trang nên tự làm): 2 ngón thu/phóng, phóng to rồi thì 1 ngón kéo ảnh,
     * chạm đúp phóng to/thu về. Ở cỡ thường, vuốt ngang = bấm nút chuyển ảnh của Zalo (vẫn trong DOM, chỉ bị ẩn):
     * sang trái là ảnh sau (nút xuống, như đi xuống cột ảnh nhỏ), sang phải là ảnh trước.
     */
    var pz = { s: 1, x: 0, y: 0, el: null }, g = null, lastTap = 0, tapTimer = null;
    // Zalo tự xử lý click trên ảnh (dựng lại khung ảnh): chặn, vì chạm trên ảnh do đoạn mã này xử lý.
    document.addEventListener('click', function (e) {
      if (document.documentElement.classList.contains('zd-m') && e.isTrusted &&
          e.target.closest && e.target.closest('.mediaViewerMain')) {
        e.stopPropagation();
        e.preventDefault();
      }
    }, true);
    function pzApply() {
      if (!pz.el) return;
      var r = pz.el.getBoundingClientRect(), w = r.width / pz.s, h = r.height / pz.s;
      var mx = w * (pz.s - 1) / 2, my = h * (pz.s - 1) / 2;
      pz.x = Math.max(-mx, Math.min(mx, pz.x));
      pz.y = Math.max(-my, Math.min(my, pz.y));
      pz.el.style.transform = pz.s === 1 ? '' : 'translate(' + pz.x + 'px,' + pz.y + 'px) scale(' + pz.s + ')';
    }
    function pzReset(el) {
      if (pz.el && pz.el !== el) pz.el.style.transform = '';
      pz = { s: 1, x: 0, y: 0, el: el || null };
      if (el) el.style.transform = '';
    }
    function dist(t) { return Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY); }
    function mid(t) { return { x: (t[0].clientX + t[1].clientX) / 2, y: (t[0].clientY + t[1].clientY) / 2 }; }
    document.addEventListener('touchstart', function (e) {
      if (!document.documentElement.classList.contains('zd-m')) return;
      var main = e.target.closest && e.target.closest('.mediaViewerMain');
      // Ảnh: .media-viewer-main-view; video: không có lớp đó, dùng khung #imageShowMainImage.
      var el = main && (main.querySelector('.media-viewer-main-view') || main.querySelector('#imageShowMainImage'));
      if (!el || sliding) { g = null; return; }
      if (shown && !shown.isConnected) shown = null; // trình xem đã đóng / dựng lại
      if (pz.el !== el) pzReset(el);
      el.classList.add('zd-touching');
      var t = e.touches;
      if (t.length >= 2) {
        revealZalo(main); // phóng to: dùng ảnh của Zalo
        g = { pinch: true, d: dist(t), s: pz.s, m: mid(t), x: pz.x, y: pz.y };
      } else {
        // cur: thứ đang hiện — lớp ảnh HD của shim (sau một lần vuốt) hoặc khung ảnh của Zalo.
        g = { x0: t[0].clientX, y0: t[0].clientY, x: pz.x, y: pz.y, t: Date.now(), s: pz.s,
          view: main.closest('.media-viewer__body__left'), main: main, cur: shown || main };
      }
    }, { capture: true, passive: true });
    document.addEventListener('touchmove', function (e) {
      if (!g || !pz.el) return;
      var t = e.touches;
      if (g.pinch && t.length >= 2) {
        var m = mid(t);
        pz.s = Math.max(1, Math.min(5, g.s * dist(t) / g.d));
        pz.x = g.x + m.x - g.m.x;
        pz.y = g.y + m.y - g.m.y;
        pzApply();
      } else if (!g.pinch && t.length === 1 && pz.s > 1) {
        pz.x = g.x + t[0].clientX - g.x0;
        pz.y = g.y + t[0].clientY - g.y0;
        pzApply();
      } else if (!g.pinch && t.length === 1 && g.view && !sliding) {
        // Cỡ thường: kéo ngang thì ảnh đi theo ngón tay (hết ảnh ở phía đó thì kéo nặng tay, như cao su).
        var dx = t[0].clientX - g.x0, dy = t[0].clientY - g.y0;
        if (!g.drag && Math.abs(dx) > 8 && Math.abs(dx) > Math.abs(dy)) {
          g.drag = true;
          g.rect = boxRect(g.view, g.main); // đo trước khi dịch
        }
        if (g.drag) {
          var has = !!navButton(g.view, dx < 0);
          if (!has) dx *= 0.35;
          g.cur.style.transition = 'none';
          g.cur.style.transform = 'translate3d(' + dx + 'px,0,0)';
          // Ảnh kế bên phía đang kéo tới (đổi hướng thì đổi ảnh).
          if (has && (!ghost || ghost.next !== (dx < 0))) makeGhost(g.view, g.rect, dx < 0);
          else if (!has) dropGhost();
          if (ghost) {
            ghost.el.style.transition = 'none';
            ghost.el.style.transform = 'translate3d(' + (ghostOff(g.rect, ghost.next) + dx) + 'px,0,0)';
          }
        }
      }
    }, { capture: true, passive: true });
    document.addEventListener('touchend', function (e) {
      var s = g;
      if (!s || !pz.el) return;
      if (e.touches.length) { // nhấc 1 trong 2 ngón: tiếp tục kéo bằng ngón còn lại
        g = { x0: e.touches[0].clientX, y0: e.touches[0].clientY, x: pz.x, y: pz.y, t: 0, s: pz.s, pinch: false };
        return;
      }
      g = null;
      pz.el.classList.remove('zd-touching');
      if (s.pinch || !e.changedTouches.length) return;
      var dx = e.changedTouches[0].clientX - s.x0, dy = e.changedTouches[0].clientY - s.y0;
      if (Math.abs(dx) < 10 && Math.abs(dy) < 10) { // chạm 1 lần: ẩn/hiện thanh; chạm đúp: phóng to / thu về
        clearTimeout(tapTimer);
        if (Date.now() - lastTap < 300) {
          lastTap = 0;
          if (s.main) revealZalo(s.main);
          if (pz.s > 1) { pz.s = 1; pz.x = pz.y = 0; } else { pz.s = 2.5; }
          pzApply();
        } else {
          lastTap = Date.now();
          var mv = pz.el.closest('.media-viewer');
          tapTimer = setTimeout(function () { if (mv) mv.classList.toggle('zd-bare'); }, 300);
        }
        return;
      }
      if (s.s > 1 || !s.view) return;
      var btn = navButton(s.view, dx < 0);
      // Kéo quá 1/4 bề ngang, hoặc hất nhanh (> 50px trong 300 ms): sang ảnh kế; không thì ảnh bật về chỗ cũ.
      var w = s.main.getBoundingClientRect().width || innerWidth;
      var fling = Math.abs(dx) > 50 && Date.now() - s.t < 300;
      if (!btn || Math.abs(dx) < Math.abs(dy) * 1.5 || (Math.abs(dx) < w / 4 && !fling)) {
        if (s.drag) { // bật về chỗ cũ, ảnh kế bên lùi ra
          s.cur.style.transition = 'transform .2s ' + EASE;
          s.cur.style.transform = '';
          if (ghost) {
            var gh = ghost;
            ghost = null;
            gh.el.style.transition = 'transform .2s ' + EASE;
            gh.el.style.transform = 'translate3d(' + ghostOff(s.rect, gh.next) + 'px,0,0)';
            setTimeout(function () { gh.el.remove(); }, 220);
          }
        }
        return;
      }
      safe(function () { slideTo(s.view, s.cur, s.main, btn, dx < 0 ? -1 : 1, s.rect); });
    }, { capture: true, passive: true });

    /** Nút chuyển ảnh (đã ẩn) của Zalo: ảnh mới hơn (xuống) / cũ hơn (lên); null nếu đã hết ảnh ở phía đó. */
    function navButton(view, next) {
      var b = view.querySelector(next ? '.navBtn.fa-Chevron_Down_24_Line' : '.navBtn.fa-Chevron_Up_24_Line');
      // Zalo không tắt nút ở đầu / cuối: xem dải ảnh nhỏ (cũ → mới, ảnh đang xem có -selected) còn ảnh ở phía đó không.
      var th = [].slice.call((view.closest('.media-viewer') || document).querySelectorAll('.imageShowSliderThumb'));
      var i = th.findIndex(function (x) { return x.classList.contains('-selected'); });
      if (b && i >= 0 && (next ? i === th.length - 1 : i === 0)) return null;
      return b;
    }
    /** Media đang hiện (ảnh / video) — để biết Zalo đã thay sang ảnh mới chưa. */
    function viewSrc(view) {
      var m = view.querySelector('.mediaViewerMain img, .mediaViewerMain video');
      return m ? m.currentSrc || m.src || '' : '';
    }
    /*
     * Ảnh kế bên khi vuốt: ảnh nhỏ của mục bên cạnh trong dải ảnh (Zalo đã tải sẵn) phóng vừa khung, đặt sát khung ảnh
     * (cách 16px) và đi theo ngón tay — kéo tới đâu thấy ảnh kế tới đó, không có khoảng đen chờ Zalo tải ảnh mới.
     */
    var ghost = null, GAP = 16;
    function boxRect(view, box) {
      var vr = view.getBoundingClientRect(), r = box.getBoundingClientRect();
      return { left: r.left - vr.left, top: r.top - vr.top, width: r.width || innerWidth, height: r.height };
    }
    function ghostOff(rect, next) { return (next ? 1 : -1) * (rect.width + GAP); }
    function thumbs(view) {
      return [].slice.call((view.closest('.media-viewer') || document).querySelectorAll('.imageShowSliderThumb'));
    }
    /** Ảnh của mục bên cạnh: { src: ảnh HD đã tải sẵn, chưa xong thì ảnh nhỏ; hd: ảnh HD đang tải; video }. */
    function neighbor(view, next) {
      var th = thumbs(view), i = th.findIndex(function (x) { return x.classList.contains('-selected'); });
      var n = i >= 0 ? th[next ? i + 1 : i - 1] : null;
      if (!n) return null;
      var hd = preloaded[hdOf(n)], img = n.querySelector('img');
      var small = img ? img.currentSrc || img.src || '' : '';
      var ready = hd && hd.complete && hd.naturalWidth;
      return { src: ready ? hd.src : small, hd: ready ? null : hd, video: isVideo(n) };
    }

    /*
     * Tải sẵn ảnh HD quanh ảnh đang xem (4 ảnh mỗi bên) ngay khi mở trình xem và sau mỗi lần chuyển: Zalo chỉ tải + giải
     * mã ảnh đang xem (JXL giải mã bằng JS, chậm). Máy chủ có bản JPG của cùng ảnh (/gr/jxl/ → /gr/jpg/), trình duyệt
     * giải mã nhanh — dùng làm ảnh kế bên khi vuốt. Link HD lấy từ dữ liệu tin của ô ảnh nhỏ (props "message").
     */
    var preloaded = {};
    function thumbMsg(th) {
      var fk = Object.keys(th).filter(function (k) { return k.indexOf('__react') === 0; })[0];
      for (var f = fk && th[fk], i = 0; f && i < 5; f = f.return, i++) {
        var m = f.memoizedProps && f.memoizedProps.message;
        if (m && m.message && typeof m.message === 'object') return m;
      }
      return null;
    }
    function isVideo(th) {
      var m = thumbMsg(th);
      return !!m && (m.msgType === 18 || /video/i.test(String(m.message.href || '')));
    }
    function hdOf(th) {
      var fk = Object.keys(th).filter(function (k) { return k.indexOf('__react') === 0; })[0];
      for (var f = fk && th[fk], i = 0; f && i < 5; f = f.return, i++) {
        var m = f.memoizedProps && f.memoizedProps.message;
        if (!m || !m.message || typeof m.message !== 'object') continue;
        var c = m.message, pr = {};
        try { pr = typeof c.params === 'string' ? JSON.parse(c.params || '{}') : (c.params || {}); } catch (e) { /* ignore */ }
        // Video: ảnh bìa; ảnh: bản HD.
        var u = m.msgType === 18 || /video/i.test(String(c.href || '')) ? c.thumbUrl : pr.hd || c.hdUrl || c.oriUrl || c.normalUrl;
        if (typeof u !== 'string' || !/^https:/.test(u)) return '';
        return u.replace('/gr/jxl/', '/gr/jpg/').replace(/\.jxl(\?|$)/, '.jpg$1');
      }
      return '';
    }
    function preloadAround(view) {
      var th = thumbs(view), i = th.findIndex(function (x) { return x.classList.contains('-selected'); });
      if (i < 0) return;
      for (var k = Math.max(0, i - 4); k <= Math.min(th.length - 1, i + 4); k++) {
        var u = k === i ? '' : hdOf(th[k]);
        if (!u || preloaded[u]) continue;
        var im = new Image();
        im.decoding = 'async';
        im.src = u;
        if (im.decode) im.decode().catch(function () { /* ignore */ });
        preloaded[u] = im;
      }
      var keys = Object.keys(preloaded);
      if (keys.length > 40) keys.slice(0, keys.length - 40).forEach(function (u) { delete preloaded[u]; }); // giữ vừa phải
    }
    // Trình xem đang mở: kiểm tra mỗi 0,7 s (dải ảnh nhỏ Zalo dựng trễ, ảnh đang xem đổi) — ảnh đã tải thì bỏ qua.
    setInterval(function () {
      var v = document.querySelector('.media-viewer .media-viewer__body__left');
      if (v) safe(function () { preloadAround(v); });
    }, 700);
    function makeGhost(view, rect, next) {
      dropGhost();
      var nb = neighbor(view, next);
      if (!nb || !nb.src || !rect) return;
      var el = document.createElement('div');
      el.className = 'zd-swipe-ghost';
      el.style.left = rect.left + 'px';
      el.style.top = rect.top + 'px';
      el.style.width = rect.width + 'px';
      el.style.height = rect.height + 'px';
      var im = document.createElement('img');
      im.src = nb.src;
      el.appendChild(im);
      // Đang hiện ảnh nhỏ: ảnh HD tải xong thì thay (cùng phần tử, không nháy).
      if (nb.hd) nb.hd.addEventListener('load', function () { im.src = nb.hd.src; });
      view.appendChild(el);
      ghost = { el: el, next: next, video: nb.video };
    }
    function dropGhost() {
      if (ghost) ghost.el.remove();
      ghost = null;
    }

    /*
     * Lớp ảnh đang hiện sau khi vuốt (shown): chính ảnh kế bên vừa trượt vào — ảnh HD JPG tải sẵn, giữ nguyên làm ảnh
     * đang xem, không mờ dần sang ảnh của Zalo (đổi lớp là nháy). Khung ảnh của Zalo vẫn chuyển theo (nút tải về / chia
     * sẻ đúng ảnh) nhưng trong suốt bên dưới (opacity, vẫn nhận chạm). Trả lại cho Zalo khi phóng to, hoặc khi tới video
     * (phải bấm phát được trên trình phát của Zalo).
     */
    var shown = null;
    function setShown(el, main) {
      if (shown && shown !== el) shown.remove();
      shown = el;
      main.style.opacity = '0';
    }
    function revealZalo(main) {
      if (shown) { shown.remove(); shown = null; }
      if (main) main.style.opacity = '';
    }

    /*
     * Chuyển ảnh: thứ đang hiện (cur) trượt ra, ảnh kế bên trượt vào giữa cùng lúc, thành ảnh đang hiện; bấm nút chuyển của
     * Zalo (Zalo tải ảnh của nó bên dưới, không phải chờ). Vuốt tiếp được ngay sau khi trượt xong. dir: -1 vuốt sang
     * trái (ảnh mới hơn), 1 sang phải (cũ hơn). Không có ảnh nhỏ (dải ảnh chưa tải): khung Zalo trượt ra, chờ ảnh mới
     * (tối đa 1,5 s) rồi trượt vào.
     */
    var sliding = false, EASE = 'cubic-bezier(.2,.8,.2,1)';
    function slideTo(view, cur, main, btn, dir, rect) {
      sliding = true;
      rect = rect || boxRect(view, main);
      if (!ghost) makeGhost(view, rect, dir < 0);
      var w = rect.width + GAP, old = viewSrc(view), gh = ghost;
      ghost = null;
      cur.style.transition = 'transform .22s ' + EASE;
      cur.style.transform = 'translate3d(' + dir * w + 'px,0,0)';
      if (gh) {
        gh.el.style.transition = 'transform .22s ' + EASE;
        gh.el.style.transform = 'translate3d(0,0,0)';
      }
      setTimeout(function () {
        if (gh) {
          if (cur !== main) cur.remove();
          main.style.transition = 'none';
          main.style.transform = '';
          gh.el.style.transition = '';
          setShown(gh.el, main);
          pzReset(null);
          btn.click();
          sliding = false;
          safe(function () { preloadAround(view); });
          if (gh.video) { // video: trình phát của Zalo hiện lên khi đã có video
            var t0 = Date.now();
            (function waitVideo() {
              var v = view.querySelector('.mediaViewerMain video');
              if (!(v && viewSrc(view) !== old) && Date.now() - t0 < 2000) { requestAnimationFrame(waitVideo); return; }
              if (shown === gh.el) revealZalo(view.querySelector('.mediaViewerMain'));
            })();
          }
          return;
        }
        revealZalo(main);
        main.style.transition = 'none';
        main.style.transform = 'translate3d(' + -dir * w + 'px,0,0)'; // chờ sẵn ở phía bên kia
        pzReset(null);
        btn.click();
        var start = Date.now();
        (function wait() {
          var m = view.querySelector('.mediaViewerMain img, .mediaViewerMain video');
          var ready = viewSrc(view) !== old && m && (m.tagName === 'VIDEO' || (m.complete && m.naturalWidth > 0));
          if (!ready && Date.now() - start < 1500) { requestAnimationFrame(wait); return; }
          void main.offsetWidth; // áp vị trí đầu trước khi bật transition
          main.style.transition = 'transform .22s ' + EASE;
          main.style.transform = '';
          setTimeout(function () { main.style.transition = ''; sliding = false; }, 240);
          safe(function () { preloadAround(view); });
        })();
      }, 220);
    }

    /** Chia sẻ ảnh đang xem sang app khác: tải ảnh gốc rồi giao cho bảng chia sẻ của Android (ZaloBridge.shareFile). */
    function shareImage() {
      var img = document.querySelector('.media-viewer #imageShowMainImage img');
      if (!img || !img.src) return;
      fetch(img.src).then(function (r) { return r.blob(); }).then(function (blob) {
        var fr = new FileReader();
        fr.onload = function () {
          var s = String(fr.result), type = blob.type || 'image/jpeg';
          var ext = (type.split('/')[1] || 'jpg').replace('jpeg', 'jpg');
          bridge.shareFile('zalo-' + Date.now() + '.' + ext, type, s.slice(s.indexOf(',') + 1));
        };
        fr.readAsDataURL(blob);
      }).catch(function (e) { log('share image failed: ' + e); });
    }
    var SHARE_ICON = '<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M18 16.08c-.76 0-1.44.3-1.96.77' +
      'L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11A2.99 2.99 0 1 0 15 5c0 .24.04.47.09.7L8.04 9.81A2.99 2.99' +
      ' 0 1 0 6 15c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65A2.92 2.92 0 1 0 18 16.08z"/></svg>';
    var FORWARD_ICON = '<svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor">' +
      '<path d="M14 5l7 7-7 7v-4.1c-5 0-8.5 1.6-11 5.1 1-5 4-10 11-11V5z"/></svg>';
    /** Nút chia sẻ duy nhất khi xem ảnh: menu chọn chuyển tiếp trong Zalo hoặc chia sẻ sang app khác. */
    function addShareButton() {
      if (document.getElementById('zd-share') || !document.body) return;
      var b = document.createElement('button');
      b.id = 'zd-share';
      b.setAttribute('aria-label', 'Chia sẻ');
      b.innerHTML = SHARE_ICON;
      var menu = document.createElement('div');
      menu.id = 'zd-share-menu';
      menu.innerHTML = '<div><button data-act="zalo">' + FORWARD_ICON + 'Chuyển tiếp trong Zalo</button>' +
        '<button data-act="apps">' + SHARE_ICON + 'Chia sẻ qua ứng dụng khác</button></div>';
      b.addEventListener('click', function (e) { e.stopPropagation(); menu.classList.add('zd-on'); });
      menu.addEventListener('click', function (e) {
        e.stopPropagation();
        menu.classList.remove('zd-on');
        var act = e.target.closest('button') && e.target.closest('button').getAttribute('data-act');
        if (act === 'zalo') {
          var fwd = document.querySelector('.media-viewer__footer .btn-forward');
          if (fwd) fwd.click();
        } else if (act === 'apps') safe(shareImage);
      });
      document.body.appendChild(b);
      document.body.appendChild(menu);
    }
    /*
     * Zalo đo khoảng trống giữa nhóm tab trên (.nav__tabs__top) và dưới (.nav__tabs__bottom) của thanh dọc để gộp
     * bớt tab (Tin nhắn, Danh bạ...) vào nút "…". Ở dock nằm ngang phép đo đó vô nghĩa: báo luôn đủ chỗ.
     */
    var origRect = Element.prototype.getBoundingClientRect;
    Element.prototype.getBoundingClientRect = function () {
      if (this.classList && document.documentElement.classList.contains('zd-m')) {
        if (this.classList.contains('nav__tabs__top')) return new DOMRect(0, 0, 64, 0);
        if (this.classList.contains('nav__tabs__bottom')) return new DOMRect(0, innerHeight, 64, 0);
      }
      return origRect.apply(this, arguments);
    };

    /*
     * Dock kính mờ. Màu gốc của dock (sáng/tối theo giao diện Zalo) đọc bằng cách tạm bỏ data-zdg — rule kính chỉ áp
     * khi có thuộc tính này — rồi pha độ trong suốt theo Cài đặt. Gọi lại khi màu trang đổi (updateBars).
     */
    var dockAlpha = cfg.dockOpacity == null ? 0.6 : Math.max(0, Math.min(100, +cfg.dockOpacity)) / 100;
    var dockColor = '';
    function updateDock() {
      var tab = document.getElementById('main-tab');
      if (!tab) return;
      tab.removeAttribute('data-zdg');
      var base = '127,127,127';
      for (var el = tab; el; el = el.parentElement) {
        var m = /rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\)/.exec(getComputedStyle(el).backgroundColor);
        if (m && (m[4] === undefined || +m[4] > 0.5)) { base = m[1] + ',' + m[2] + ',' + m[3]; break; }
      }
      var c = 'rgba(' + base + ',' + dockAlpha + ')';
      // Viên thuốc tab đang chọn: tối hơn trên dock sáng (xanh), sáng hơn trên dock tối.
      var rgb = base.split(',').map(Number), lum = (0.299 * rgb[0] + 0.587 * rgb[1] + 0.114 * rgb[2]) / 255;
      document.documentElement.style.setProperty('--zd-ind', lum < 0.3 ? 'rgba(255,255,255,.14)' : 'rgba(0,0,0,.16)');
      if (c !== dockColor) {
        dockColor = c;
        document.documentElement.style.setProperty('--zd-dock', c);
      }
      tab.setAttribute('data-zdg', '');
      if (api.syncDock) api.syncDock();
    }
    api.updateDock = function (force) { if (force || !dockColor) safe(updateDock); };
    var dockAutoHide = cfg.dockAutoHide !== false;
    function showDock(show) { document.documentElement.classList.toggle('zd-dockhide', !show); if (api.syncDock) api.syncDock(); }
    /** Khoảng cách từ dock tới đáy màn hình (px), theo Cài đặt. */
    function setDockBottom(px) {
      if (px == null) return;
      document.documentElement.style.setProperty('--zd-dock-bottom', Math.max(0, Math.min(200, +px || 0)) + 'px');
    }
    setDockBottom(cfg.dockBottom);
    api.setDock = function (opacity, autoHide, bottom) {
      dockAlpha = Math.max(0, Math.min(100, +opacity || 0)) / 100;
      dockAutoHide = !!autoHide;
      setDockBottom(bottom);
      if (!dockAutoHide) showDock(true);
      safe(updateDock);
    };

    /*
     * Vùng cuộn nằm dưới dock (danh sách chat, danh bạ): lần đầu cuộn thì thêm khoảng trống cuối (data-zdpad).
     * Tự ẩn dock: cuộn xuống xem tiếp thì ẩn, cuộn ngược lên (hoặc về đầu danh sách) thì hiện lại.
     */
    var lastScroller = null, lastScrollTop = 0, lastSwipe = 0, swipeY = null;
    function inList(t) { return t instanceof Element && !!t.closest('#sidebarNav, .contact-pages') && !t.closest('#main-tab'); }
    // Chỉ ẩn/hiện theo cuộn do người dùng vuốt trên danh sách (kể cả quán tính sau khi nhấc tay), không theo cuộn tự
    // động của Zalo (vd. khôi phục vị trí danh sách khi đóng hội thoại).
    document.addEventListener('touchstart', function (e) {
      swipeY = inList(e.target) && e.touches.length === 1 ? e.touches[0].clientY : null;
    }, { capture: true, passive: true });
    document.addEventListener('touchmove', function (e) {
      if (swipeY === null) return;
      lastSwipe = Date.now();
      // Vuốt ngón tay xuống = kéo nội dung lên: hiện lại, kể cả khi danh sách đã ở trên cùng (không còn sự kiện cuộn).
      if (dockAutoHide && e.touches[0].clientY - swipeY > 24) showDock(true);
    }, { capture: true, passive: true });
    document.addEventListener('scroll', function (e) {
      var t = e.target;
      if (!inList(t)) return;
      if (t.closest('.resize-triggers')) return; // bộ đo kích thước của Zalo (cuộn giả), không phải danh sách
      if (!t.hasAttribute('data-zdpad')) t.setAttribute('data-zdpad', '');
      var y = t.scrollTop, prev = lastScroller === t ? lastScrollTop : y;
      lastScroller = t;
      lastScrollTop = y; // luôn theo vị trí mới, kể cả cuộn tự động, để lần vuốt sau so đúng
      if (!dockAutoHide || Date.now() - lastSwipe > 1500) return;
      if (y <= 8 || prev - y > 6) showDock(true);
      else if (y - prev > 6) showDock(false);
      else lastScrollTop = prev; // chưa đủ 6px: cộng dồn với lần sau
    }, true);
    // Chuyển tab hay mở / đóng hội thoại: luôn hiện lại dock.
    document.addEventListener('click', function () { if (dockAutoHide) showDock(true); }, true);
    api.showDock = function () { showDock(true); };
    /** Danh sách chat native cuộn (xem mục 12): ẩn/hiện dock và thanh tìm kiếm như khi cuộn danh sách web. */
    api.nativeScroll = function (dy, atTop) {
      if (!dockAutoHide) return;
      if (atTop || dy < -6) showDock(true);
      else if (dy > 6) showDock(false);
    };

    function addStyle() {
      if (addStyle.done) return;
      try {
        // Stylesheet dựng bằng CSSOM: không bị chặn bởi Content-Security-Policy của trang.
        var sheet = new CSSStyleSheet();
        sheet.replaceSync(CSS);
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
        addStyle.done = true;
        return;
      } catch (e) { /* trình duyệt cũ: dùng thẻ <style> */ }
      if (!document.head) return;
      var s = document.createElement('style');
      s.id = 'zd-mobile-css';
      s.textContent = CSS;
      document.head.appendChild(s);
      addStyle.done = true;
    }

    /** Tạo nút ← của app (gắn vào body, vị trí đặt bằng style top/height). */
    function backButton(id, onClick) {
      if (!document.body) return null;
      var b = document.createElement('button');
      b.id = id;
      b.type = 'button';
      b.setAttribute('aria-label', 'Quay lại');
      var svgNS = 'http://www.w3.org/2000/svg';
      var svg = document.createElementNS(svgNS, 'svg');
      svg.setAttribute('viewBox', '0 0 24 24');
      svg.setAttribute('width', '28');
      svg.setAttribute('height', '28');
      var path = document.createElementNS(svgNS, 'path');
      path.setAttribute('d', 'M15.4 7.4 14 6l-6 6 6 6 1.4-1.4-4.6-4.6z');
      path.setAttribute('fill', 'currentColor');
      svg.appendChild(path);
      b.appendChild(svg);
      b.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        onClick();
      });
      document.body.appendChild(b);
      return b;
    }

    /** Nút ← của app, chỉ hiện khi khung chat không có nút "<" của Zalo (bố cục màn hình rộng). */
    function placeBack() {
      var b = document.getElementById('zd-back');
      var header = M.main ? M.main.querySelector('header') : null;
      var need = M.chat && !!M.main && !zaloBack();
      if (header) header.toggleAttribute('data-zd', false);
      if (!need) {
        if (b) b.classList.remove('zd-on');
        return;
      }
      if (!b) b = backButton('zd-back', api.mobileHome);
      if (!b) return;
      if (header) header.setAttribute('data-zd', 'header');
      var r = header ? rectOf(header) : null;
      b.style.top = (r ? Math.round(r.top) : 8) + 'px';
      b.style.height = (r ? Math.round(r.height) : 44) + 'px';
      b.classList.add('zd-on');
    }
    M.placeBack = placeBack;

    /** Nút "<" của Zalo trong khung chat: chạy hiệu ứng trượt ra trước (api.mobileHome tự bấm lại nút này sau). */
    document.addEventListener('click', function (e) {
      if (passBack || !M.main || !(e.target instanceof Element)) return;
      var b = e.target.closest('.conv-back-btn');
      if (!b || !M.main.contains(b)) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      api.mobileHome();
    }, true);

    /**
     * Mở hội thoại thì Zalo gọi focus() vào ô nhập tin: bật bàn phím (khác app điện thoại) và làm trang phải
     * tính lại bố cục 2 lần (lần focus, lần bàn phím đổi kích thước) — phần lớn độ giật khi mở chat.
     * Chỉ cho focus tự động khi người dùng vừa chạm vào trong khung chat (nút Trả lời, emoji, Gửi...) hoặc vào bảng nổi
     * của nó (danh sách @nhắc tên, bảng biểu cảm... nằm ngoài khung chat, trong body). Chạm vào danh sách chat thì không.
     * Chạm thẳng vào ô nhập vẫn mở bàn phím bình thường (trình duyệt tự focus, không qua hàm này).
     */
    var lastTouch = { t: 0, el: null };
    document.addEventListener('pointerdown', function (e) { lastTouch = { t: Date.now(), el: e.target }; }, true);
    var origFocus = HTMLElement.prototype.focus;
    HTMLElement.prototype.focus = function () {
      if (this.isContentEditable && document.activeElement !== this) {
        var chat = this.closest('#container main');
        var el = lastTouch.el instanceof Element ? lastTouch.el : null;
        var touchedChat = Date.now() - lastTouch.t < 1500 && !!el && !!chat &&
          (chat.contains(el) || !!el.closest('body > .popover-v3, body > [class*=popover], body > [class*=mention]'));
        if (chat && !touchedChat) return undefined;
      }
      return origFocus.apply(this, arguments);
    };

    /** Theo dõi khung chat: Zalo chèn <main> khi mở hội thoại, gỡ đi khi bấm "<". */
    function sync() {
      var ok = !!document.getElementById('sidebarNav');
      if (ok !== M.ok) {
        M.ok = ok;
        log(ok ? 'mobile layout detected: ' + innerWidth + 'px' : 'mobile layout lost');
      }
      var main = findMain();
      if (main !== M.main) {
        if (M.main) M.main.removeAttribute('data-zd');
        M.main = main;
        if (main) main.setAttribute('data-zd', 'main');
        setChat(!!main);
      }
      syncInfo();
    }

    /*
     * Khung bên phải trong hội thoại: Zalo chèn <aside> vào <main> (đo trong app, 10/2026):
     *   - Thông tin hội thoại: aside.compact-chat-info (rộng 280px, hiệu ứng expand-width-from-right), có nền mờ
     *     .cp-bg phủ phần chat còn lại — bấm vào đó là đóng.
     *   - Tìm kiếm trong trò chuyện: aside.search-message-panel-compact (chuyển động width), nút X
     *     .search-message-inchat__header__right-btn.
     * CSS phủ toàn màn hình ngay theo bộ chọn (không chờ script); ở đây chỉ gắn nút ←, nhận ra lúc Zalo thay khung
     * (Thông tin → Tìm kiếm: không trượt vào lại) và để Back đóng khung trước khi rời hội thoại.
     */
    var infoEl = null, infoGoneAt = 0;
    function syncInfo() {
      var info = M.main && M.chat ? M.main.querySelector(':scope > aside') : null;
      if (info === infoEl) return;
      if (infoEl) {
        infoEl.removeAttribute('data-zdinfo');
        infoGoneAt = Date.now();
      }
      infoEl = info;
      if (info) {
        info.classList.toggle('zd-swap', Date.now() - infoGoneAt < 700);
        info.setAttribute('data-zdinfo', '');
      }
      document.documentElement.classList.toggle('zd-info', !!info);
      placeInfoBack(info);
    }
    // Chạy ngay trong MutationObserver (trước khi trình duyệt vẽ) để kịp gắn zd-swap trước khung hình đầu tiên.
    function quickInfo() {
      safe(syncInfo); safe(placeVideoControls); safe(scrollThumbStrip); safe(syncHeader); safe(syncIndicator); safe(syncDock);
    }

    /** Viên thuốc #zd-ind trong dock: đặt dưới nút đang chọn (.leftbar-tab.selected); đổi nút thì trượt sang. */
    var indKey = '';
    function syncIndicator() {
      var tab = document.getElementById('main-tab');
      if (!tab) return;
      var ind = document.getElementById('zd-ind');
      if (!ind) {
        ind = document.createElement('div');
        ind.id = 'zd-ind';
      }
      if (ind.parentElement !== tab) tab.insertBefore(ind, tab.firstChild);
      var sel = tab.querySelector('.leftbar-tab.selected');
      if (!sel || !sel.offsetWidth) { ind.classList.remove('zd-on'); indKey = ''; return; }
      var key = sel.offsetLeft + ',' + sel.offsetWidth; // offsetParent là #main-tab (fixed)
      if (key === indKey) return;
      indKey = key;
      ind.style.transform = 'translateX(' + sel.offsetLeft + 'px)';
      ind.style.width = sel.offsetWidth + 'px';
      ind.classList.add('zd-on');
    }
    addEventListener('resize', function () { indKey = ''; safe(syncIndicator); });

    /*
     * Dock native (app vẽ, nằm trên WebView — NativeDock): báo cho app các nút (biểu tượng, số chưa đọc, nút đang
     * chọn), hiện / trượt ẩn, màu. App bật (api.nativeDock) thì dock của trang chỉ còn lớp làm nhòe nền (zd-ndock).
     * Chạm nút ở dock native: api.dockTap bấm nút tương ứng của trang (đã ẩn).
     */
    /*
     * Các nút của dock theo thứ tự hiện: Danh bạ, Tin nhắn, My Documents, [zCloud], [Công cụ] — zCloud / Công cụ theo Cài
     * đặt. Dock rộng theo số nút (--zd-dock-w; dock native vẽ cùng bề rộng, kính mờ khớp).
     */
    var dockCloud = cfg.dockCloud === true, dockTools = cfg.dockTools === true, lastDock = '';
    function dockKeys() {
      return ['STR_TAB_CONTACT', 'STR_TAB_MESSAGE', 'KEY_NAME_SEND2ME', dockCloud ? 'STR_LEFT_SIDEBAR_ZALO_CLOUD' : '',
        dockTools ? 'STR_TOOLS' : ''].filter(Boolean);
    }
    // Dock co giãn vừa số nút (mỗi nút ~ 71px như dock 3 nút ban đầu, 5 nút hẹp lại cho vừa màn hình nhỏ).
    function dockWidth() { return { 3: 228, 4: 290, 5: 330 }[dockKeys().length] || 228; }
    function applyDockButtons() {
      var root = document.documentElement;
      root.classList.toggle('zd-dk-cloud', dockCloud);
      root.classList.toggle('zd-dk-tools', dockTools);
      root.style.setProperty('--zd-dock-w', dockWidth() + 'px');
    }
    applyDockButtons();
    api.setDockButtons = function (cloud, tools) {
      dockCloud = !!cloud;
      dockTools = !!tools;
      applyDockButtons();
      lastDock = '';
      setTimeout(function () { safe(syncDock); }, 50);
    };
    function dockButton(i) {
      var k = dockKeys()[i];
      return k ? document.querySelector('#main-tab .leftbar-tab[data-translate-title=' + k + ']') : null;
    }
    /**
     * Số trên nút (.z-noti-badge của Zalo): "1".."5", "5+", "N"; chấm không số: "."; không có: "". Zalo vẽ số bằng
     * biểu tượng của font (i.fa-1_24_Line ... fa-5_Plus_24_Line, fa-N_24_Line), không phải chữ.
     */
    function tabBadge(b) {
      var badge = b.querySelector('.z-noti-badge');
      if (!badge) return '';
      var t = badge.textContent.trim();
      if (t) return t.length <= 4 ? t : '';
      var i = badge.querySelector('i.fa');
      var m = i && /\bfa-(\d+|N)(_Plus)?_24/.exec(i.className);
      return m ? m[1] + (m[2] ? '+' : '') : '.';
    }
    function syncDock() {
      var root = document.documentElement, tab = document.getElementById('main-tab');
      if (!tab || !M.ok) return; // trang chưa dựng xong: app giữ dock lần trước (mở app), màn đăng nhập thì app tự ẩn
      var color = '', items = dockKeys().map(function (k, n) {
        var b = dockButton(n), i = b && (b.querySelector('i.internal-icon') || b.querySelector('i.fa:not(.z-noti-badge i)'));
        if (!i) return null;
        var ic = [].filter.call(i.classList, function (c) { return c.indexOf('fa-') === 0; })[0] || '';
        if (!color) color = getComputedStyle(i).color;
        return [ic.slice(3), tabBadge(b), b.classList.contains('selected') ? 1 : 0, parseFloat(getComputedStyle(i).fontSize) || 28, k];
      });
      var searching = root.classList.contains('zd-searching');
      // Hộp thoại, trình xem ảnh, menu giữ lâu (#zd-focus) phủ lên dock của trang: dock native (nằm trên) cũng phải ẩn.
      var cover = !!document.querySelector('.zl-modal, .media-viewer, #zd-focus.on, .popover-v3.zd-dock-full');
      var msgItem = items.filter(function (x) { return x && x[4] === 'STR_TAB_MESSAGE'; })[0];
      var st = JSON.stringify({ v: !!(M.ok && tab && root.classList.contains('zd-m') && !M.chat && !cover && msgItem), w: dockWidth(),
        dh: root.classList.contains('zd-dockhide') || searching, t: items, c: dockColor, ic: color,
        ind: root.style.getPropertyValue('--zd-ind') });
      if (st !== lastDock) { lastDock = st; bridge.dockState(st); }
    }
    api.syncDock = function () { safe(syncDock); };
    api.nativeDock = function (on) {
      document.documentElement.classList.toggle('zd-ndock', !!on);
      lastDock = '';
      safe(syncDock);
    };
    /*
     * zCloud / Công cụ mở popup neo vào nút của dock trang theo kiểu thanh bên máy tính (đè lên dock hoặc ra ngoài mép
     * trên): đặt lại ngay trên dock, giữa màn hình. Popup giới thiệu zCloud bị ẩn cùng các quảng cáo zCloud tự bật (CSS
     * mục 8) — chỉ cho hiện khi người dùng vừa bấm nút (zd-zc-open, gỡ khi popup đóng).
     */
    function placeDockPopover(k) {
      var root = document.documentElement, before = [].slice.call(document.querySelectorAll('.popover-v3'));
      if (k === 'STR_LEFT_SIDEBAR_ZALO_CLOUD') root.classList.add('zd-zc-open');
      var t0 = Date.now();
      (function find() {
        // zCloud: popup có nội dung giới thiệu zCloud (có thể còn nằm sẵn trong trang từ lần bấm trước); Công cụ: popup mới.
        var pop = [].filter.call(document.querySelectorAll('.popover-v3'), function (x) {
          if (x.offsetHeight < 20) return false;
          var zc = !!x.querySelector('.zcloud-promo-content');
          return k === 'STR_LEFT_SIDEBAR_ZALO_CLOUD' ? zc : !zc && before.indexOf(x) < 0;
        }).pop();
        if (!pop) {
          if (Date.now() - t0 < 1500) requestAnimationFrame(find);
          else root.classList.remove('zd-zc-open');
          return;
        }
        // Toàn màn hình (lớp CSS zd-dock-full — React của Zalo ghi lại style trên phần tử nên không đặt style trực tiếp);
        // dock native ẩn đi (syncDock coi như bị che); Back đóng popup như menu thường (api.back) → về Tin nhắn.
        pop.classList.add('zd-dock-full');
        if (api.syncDock) api.syncDock();
        var watch = setInterval(function () { // popup đóng: hết cho phép popup zCloud, dock hiện lại
          if (pop.isConnected && pop.offsetHeight) return;
          clearInterval(watch);
          pop.classList.remove('zd-dock-full');
          root.classList.remove('zd-zc-open');
          if (api.syncDock) api.syncDock();
        }, 500);
      })();
    }
    api.dockTap = function (i) {
      var b = dockButton(i), k = dockKeys()[i];
      if (k === 'STR_LEFT_SIDEBAR_ZALO_CLOUD' || k === 'STR_TOOLS') placeDockPopover(k);
      if (b) b.click();
      // App đã cho viên thuốc trượt trước: báo lại trạng thái thật kể cả khi Zalo không đổi tab.
      setTimeout(function () { lastDock = ''; safe(syncDock); }, 300);
    };
    if (cfg.nativeList) document.documentElement.classList.add('zd-ndock');

    /*
     * Chạm nút khác ở dock: ghi hướng (nút mới bên phải → trang mới trượt từ phải vào). Lần Zalo đổi trang kế tiếp
     * trong cột danh sách (thêm trang mới, hoặc gỡ trang Danh bạ để lộ lại danh sách chat), gắn hiệu ứng lên đúng trang
     * đang hiện một lần, gỡ khi chạy xong.
     */
    var slide = null;
    document.addEventListener('click', function (e) {
      var t = e.target instanceof Element && e.target.closest('#main-tab .leftbar-tab');
      var cur = document.querySelector('#main-tab .leftbar-tab.selected');
      if (!t || !cur || t === cur) return;
      slide = { fwd: t.offsetLeft > cur.offsetLeft, t: Date.now() };
    }, true);
    function slidePage(recs) {
      if (!slide || Date.now() - slide.t > 1000) return;
      var col = document.querySelector('#sidebarNav > :not(#main-tab)');
      if (!col || !recs.some(function (r) { return r.target === col; })) return;
      var page = [].filter.call(col.children, function (c) { return c.id !== 'contact-search' && c.offsetHeight > 0; }).pop();
      if (!page) return;
      var cls = slide.fwd ? 'zd-enter-fwd' : 'zd-enter-back';
      slide = null;
      page.classList.add(cls);
      page.addEventListener('animationend', function done(ev) {
        if (ev.target !== page) return;
        page.classList.remove(cls);
        page.removeEventListener('animationend', done);
      });
    }

    /*
     * Thanh trên danh sách chat / danh bạ (#contact-search): [avatar][tên tài khoản][tìm kiếm][thêm bạn][tạo nhóm].
     * Avatar + tên (#zd-head, của app) chèn đầu thanh, chép từ avatar ở dock (.nav__tabs__zalo, tên ở title) — avatar
     * và nút Cài đặt bỏ khỏi dock. Chạm vào thì bấm nút Cài đặt (đã ẩn) để mở menu của Zalo, menu đặt lại ngay dưới
     * thanh (zd-acct-pop). Ô tìm kiếm của Zalo thu thành nút tròn; chạm vào là ô được focus, Zalo vào chế độ tìm kiếm
     * (zd-searching) thì ô giãn hết thanh và ẩn avatar + tên.
     */
    var acctClickAt = 0;
    function syncHeader() {
      var cs = document.getElementById('contact-search');
      var searching = !!document.querySelector('#sidebarNav #recent-search-list, #sidebarNav #searchResultList');
      var root = document.documentElement;
      if (root.classList.contains('zd-searching') && !searching) {
        // Thoát tìm kiếm: bỏ focus ô nhập (Zalo để nguyên), nếu không lần chạm sau không vào lại chế độ tìm kiếm.
        var inp = document.getElementById('contact-search-input');
        if (inp && document.activeElement === inp) inp.blur();
      }
      root.classList.toggle('zd-searching', searching);
      if (Date.now() - acctClickAt < 1500) {
        var pop = [].filter.call(document.querySelectorAll('body > .popover-v3'), function (p) {
          return !!p.querySelector('.zmenu-body, .zmenu-item');
        }).pop();
        if (pop && !pop.classList.contains('zd-acct-pop')) { pop.classList.add('zd-acct-pop'); acctClickAt = 0; }
      }
      if (!cs) return;
      var head = document.getElementById('zd-head');
      if (!head) {
        head = document.createElement('div');
        head.id = 'zd-head';
        head.innerHTML = '<div class="zd-head-av"></div><div class="zd-head-txt"><div class="zd-head-name"></div>' +
          '<div class="zd-conn"><i></i><span></span></div></div>';
        // Chạm nhãn lúc chưa kết nối: giục Zalo nối lại ngay (mục 11) thay vì mở menu tài khoản.
        head.querySelector('.zd-conn').addEventListener('click', function (e) {
          if (!api.connState || api.connState() === 'on') return;
          e.stopPropagation();
          if (api.wake) api.wake('tap status');
        });
        addEventListener('zd-conn', function () { safe(syncConn); });
        head.addEventListener('click', function (e) {
          e.stopPropagation();
          // Menu Cài đặt của dock (Thông tin tài khoản, Cài đặt, Dữ liệu, Ngôn ngữ, Hỗ trợ, Đăng xuất) gồm gần hết menu
          // avatar: mở nó, nút Cài đặt đã bỏ khỏi dock. Không có thì mở menu avatar.
          var av = document.querySelector('#main-tab [data-translate-title=STR_MENU_SETTING]') ||
            document.querySelector('#main-tab .nav__tabs__zalo .zavatar-container');
          if (!av) return;
          acctClickAt = Date.now();
          av.click();
        });
      }
      if (head.parentElement !== cs || cs.firstChild !== head) cs.insertBefore(head, cs.firstChild);
      var z = document.querySelector('#main-tab .nav__tabs__zalo'), za = z && z.querySelector('.zavatar');
      var name = (z && z.getAttribute('title')) || '', key = name + '|' + (za ? za.outerHTML : '');
      if (head.getAttribute('data-key') !== key) {
        head.setAttribute('data-key', key);
        head.querySelector('.zd-head-name').textContent = name;
        var av = head.firstChild;
        av.textContent = '';
        if (za) av.appendChild(za.cloneNode(true));
      }
      syncConn();
    }
    var CONN_TEXT = { on: 'Đã kết nối', wait: 'Đang kết nối', off: 'Mất kết nối' };
    function syncConn() {
      var c = document.querySelector('#zd-head .zd-conn'), s = api.connState ? api.connState() : 'on';
      if (!c || c.getAttribute('data-s') === s) return;
      c.setAttribute('data-s', s);
      c.lastChild.textContent = CONN_TEXT[s];
    }

    /** Dải ảnh nhỏ trong trình xem: đổi ảnh thì cuộn ảnh đang xem vào giữa dải. */
    var lastThumb = null;
    function scrollThumbStrip() {
      var th = document.querySelector('.media-viewer__body__right .img-sh-th.-selected');
      if (!th || th === lastThumb) return;
      lastThumb = th;
      var strip = th.parentElement;
      strip.scrollTo({ left: th.offsetLeft - (strip.clientWidth - th.offsetWidth) / 2, behavior: 'smooth' });
    }

    /**
     * Thanh điều khiển video trong trình xem: đặt theo màn hình (cách hai bên 12px, trên dải ảnh nhỏ: cách đáy 84px). position: fixed tính theo lớp cha gần nhất có transform/filter (khung trượt ảnh) chứ không theo
     * màn hình, nên đo lớp đó rồi bù chênh lệch.
     */
    function placeVideoControls() {
      var bars = document.querySelectorAll('.media-viewer .zvideo-player-controls-container');
      for (var i = 0; i < bars.length; i++) {
        var c = bars[i], cb = null;
        for (var el = c.parentElement; el && el !== document.documentElement; el = el.parentElement) {
          var cs = getComputedStyle(el);
          if (cs.transform !== 'none' || cs.filter !== 'none' || cs.perspective !== 'none' ||
            /transform|filter/.test(cs.willChange) || /paint|layout|strict|content/.test(cs.contain)) { cb = el; break; }
        }
        var r = cb ? rectOf(cb) : { left: 0, right: innerWidth, bottom: innerHeight };
        var s = c.style;
        s.setProperty('left', Math.round(12 - r.left) + 'px', 'important');
        s.setProperty('right', Math.round(r.right - (innerWidth - 12)) + 'px', 'important');
        s.setProperty('bottom', Math.round(r.bottom - (innerHeight - 84)) + 'px', 'important');
      }
    }
    addEventListener('resize', function () { safe(placeVideoControls); });
    function placeInfoBack(info) {
      var b = document.getElementById('zd-info-back');
      if (!info) { if (b) b.classList.remove('zd-on'); return; }
      if (!b) b = backButton('zd-info-back', api.closeInfo);
      if (!b) return;
      b.style.top = '0px';
      b.style.height = '68px'; // cao bằng thanh tiêu đề của khung (chat-info__header, search-message-inchat__header)
      b.classList.add('zd-on');
    }
    /** Đóng khung bên phải như người dùng: nút X của khung Tìm kiếm, nền mờ của khung Thông tin, nút bật/tắt khung. */
    api.closeInfo = function () {
      var info = infoEl && infoEl.isConnected ? infoEl : null;
      if (!info) return false;
      var b = info.querySelector('.search-message-inchat__header__right-btn') || info.querySelector(':scope > .cp-bg') ||
        (M.main && M.main.querySelector('header [icon^="outline-right-bar"]'));
      if (b) b.click();
      setTimeout(syncInfo, 60);
      return true;
    };

    M.sync = sync;
    // Menu Danh bạ: chạm một mục (Danh sách bạn bè, nhóm, lời mời) thì mở trang chi tiết; chạm tab ở dock thì về menu.
    document.addEventListener('click', function (e) {
      var t = e.target;
      if (!(t instanceof Element)) return;
      if (t.closest('#main-tab')) {
        if (M.cdetail) { setContactDetail(false); setTimeout(sync, 0); }
      } else if (t.closest('#sidebarNav .menu-item') && document.querySelector('#container > .contact-pages')) {
        setContactDetail(true);
        setTimeout(sync, 0);
      }
    }, true);

    /**
     * Chạm vào một hàng trong danh sách (hội thoại, kết quả tìm kiếm, mục danh bạ) thì hiện khung chat.
     * Cần cho bố cục màn hình rộng: ở đó <main> vẫn nằm trong trang (bị ẩn) sau khi quay lại danh sách.
     */
    document.addEventListener('click', function (e) {
      if (!M.ok || M.chat) return;
      var t = e.target, nav = document.getElementById('sidebarNav');
      if (!(t instanceof Element) || !nav || !nav.contains(t) || t.closest('#main-tab')) return;
      if (t.closest('input, textarea, [contenteditable=""], [contenteditable=true]')) return;
      var lw = rectOf(nav).width;
      for (var el = t; el && el !== nav; el = el.parentElement) {
        var r = rectOf(el);
        if (r.width < lw * 0.6 || r.height < 44 || r.height > 140) continue;
        if (el.querySelector('input, textarea')) return; // ô tìm kiếm, không phải hội thoại
        var p = el.parentElement, similar = 0;
        if (p) {
          for (var i = 0; i < p.children.length && i < 12; i++) {
            if (Math.abs(rectOf(p.children[i]).height - r.height) <= 8) similar++;
          }
        }
        if (similar >= 2) {
          setTimeout(function () { sync(); setChat(true); }, 150);
          return;
        }
      }
    }, true);

    var pending = false;
    function schedule() {
      if (pending) return;
      pending = true;
      setTimeout(function () { pending = false; sync(); }, 50);
    }
    function start() {
      document.documentElement.classList.add('zd-m');
      addStyle();
      addShareButton();
      sync();
      // Cho Zalo đo lại thanh tab theo số đo mới (nó tính lại khi cửa sổ đổi kích thước).
      setTimeout(function () { dispatchEvent(new Event('resize')); }, 3000);
      new MutationObserver(function (recs) { safe(function () { slidePage(recs); }); quickInfo(); schedule(); })
        .observe(document.documentElement, { childList: true, subtree: true });
      addEventListener('resize', function () { if (M.chat) placeBack(); });
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
    else start();
  });

  // ------------------------------------------------------------------ 9. Màu thanh hệ thống
  /*
   * Thanh trạng thái (trên) và thanh điều hướng (dưới) do app tô. Lấy màu nền thật của trang ở mép trên và mép
   * dưới (đổi theo giao diện sáng/tối, theo màn hình đang mở) rồi báo cho app; chỉ báo khi màu đổi.
   */
  function bgAt(x, y) {
    for (var el = document.elementFromPoint(x, y); el; el = el.parentElement) {
      var c = getComputedStyle(el).backgroundColor;
      var m = /rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\)/.exec(c);
      if (m && (m[4] === undefined || +m[4] > 0.5)) {
        return '#' + [m[1], m[2], m[3]].map(function (v) { return ('0' + (+v | 0).toString(16)).slice(-2); }).join('');
      }
    }
    return null; // toàn lớp trong suốt
  }
  var lastBars = '';
  /** App bỏ màu gửi lúc ảnh chụp còn che trang: gửi lại màu hiện tại. */
  api.refreshBars = function () { lastBars = ''; safe(updateBars); };
  function updateBars() {
    if (!document.body || isHidden(document)) return;
    var top = bgAt(innerWidth / 2, 1) || '#ffffff', bottom = bgAt(innerWidth / 2, innerHeight - 1) || '#ffffff';
    // Trình xem ảnh / video: nền đen, thanh trên là dải mờ dần (không có màu nền) — thanh hệ thống đen theo.
    if (mobile && document.querySelector('.media-viewer')) top = bottom = '#000000';
    // Danh sách native: trang trong suốt ở dưới — mép dưới, và mép trên khi thanh trên đã tự ẩn, là nền danh sách
    // (--zd-listbg, mục 12).
    else if (document.documentElement.classList.contains('zd-native')) {
      var lb = /rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)/.exec(getComputedStyle(document.documentElement).getPropertyValue('--zd-listbg'));
      if (lb) {
        bottom = '#' + [lb[1], lb[2], lb[3]].map(function (v) { return ('0' + (+v | 0).toString(16)).slice(-2); }).join('');
        if (!bgAt(innerWidth / 2, 1)) top = bottom;
      }
    }
    if (api.updateDock) api.updateDock(top + bottom !== lastBars); // đổi giao diện sáng/tối: đọc lại màu dock
    if (top + bottom === lastBars) return;
    lastBars = top + bottom;
    bridge.systemBars(top, bottom);
  }
  safe(function () { setInterval(function () { safe(updateBars); }, 1000); });

  // Báo cho app khi danh sách chat thật đã hiện (app che màn hình "Đang đăng nhập" bằng ảnh chụp lần trước tới lúc này).
  safe(function () {
    var readyTimer = setInterval(function () {
      if (!document.querySelector('#sidebarNav .msg-item, #sidebarNav [class*=conv-item]')) {
        // Giao diện native không bao giờ nhường cho trang, trừ khi trang cần người dùng: màn đăng nhập (mã QR...).
        if (!document.getElementById('sidebarNav') &&
            document.querySelector('[class*=qrcode], [class*=qr-code], [class*=login-qr], img[alt*=QR]')) {
          clearInterval(readyTimer);
          bridge.needLogin();
        }
        return;
      }
      clearInterval(readyTimer);
      bridge.pageReady();
    }, 250);
  });

  // ------------------------------------------------------------------ 10. Nhật ký chẩn đoán thông báo
  /*
   * Để biết khi lỡ thông báo thì lỗi ở đâu: Zalo Web có nhận được tin không (số chưa đọc / đầu danh sách thay đổi)
   * mà không bắn thông báo, hay không nhận được gì (mất kết nối, trang bị khoá, bị dừng). Không ghi tên hay nội dung.
   */
  safe(function () {
    if (window.opener || window.top !== window) return;
    addEventListener('online', function () { diag('browser online'); });
    addEventListener('offline', function () { diag('browser offline'); });
    document.addEventListener('freeze', function () { diag('page frozen by webview'); });
    document.addEventListener('resume', function () { diag('page resumed'); });

    function hash(s) { var h = 0; for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return h; }
    var lastBadge = null, lastTop = null, locked = false, lastBeat = Date.now(), lastTick = Date.now();
    setInterval(function () {
      safe(function () {
        var now = Date.now(), bg = isHidden(document);
        if (now - lastTick > 60000) diag('page timers paused ~' + Math.round((now - lastTick) / 1000) + 's');
        lastTick = now;

        // Số tin chưa đọc trên tab Tin nhắn ở dock.
        var badge = '', tab = document.querySelector('#main-tab [data-translate-title=STR_TAB_MESSAGE]');
        if (tab) {
          var els = tab.querySelectorAll('*');
          for (var i = 0; i < els.length; i++) {
            var t = els[i].children.length ? '' : els[i].textContent.trim();
            if (/^\d+\+?$/.test(t)) { badge = t; break; }
          }
        }
        if (lastBadge !== null && badge !== lastBadge) {
          diag('unread ' + (lastBadge || '0') + ' -> ' + (badge || '0') + (bg ? ' (background)' : ''));
        }
        lastBadge = badge;

        // Hội thoại không ghim đầu tiên có hoạt động mới (so nội dung dòng đã băm, bỏ qua nhãn thời gian "x phút").
        var top = document.querySelector('#conversationList .msg-item:not(.pinned)'), key = '';
        if (top) {
          var txt = top.textContent, tm = top.querySelector('.preview-time');
          if (tm) txt = txt.replace(tm.textContent, '');
          // Tin mới giống hệt tin trước (cùng người, cùng chữ): chỉ có nhãn thời gian đổi sang "Vài giây".
          key = hash(txt) + (tm && /giây|second|now|vừa/i.test(tm.textContent) ? '|now' : '');
        }
        if (bg && lastTop !== null && key && key !== lastTop) diag('chat list: new activity at top (background)');
        lastTop = key;

        // Zalo tự khoá: "Bạn đang mở Zalo trên một Tab khác…".
        var modal = document.querySelector('[id^=zl-modal]');
        var isLocked = !!modal && /Tab khác|tab khác|another tab/.test(modal.textContent);
        if (isLocked !== locked) diag(isLocked ? 'zalo locked: "open in another tab"' : 'zalo unlocked');
        locked = isLocked;

        if (bg && now - lastBeat > 5 * 60000) {
          diag('alive (background)' + (navigator.onLine ? '' : ' offline') + (document.getElementById('sidebarNav') ? '' : ' not logged in'));
          lastBeat = now;
        }
        if (!bg) lastBeat = now;
      });
    }, 5000);
  });

  // ------------------------------------------------------------------ 11. Tự kết nối lại khi điện thoại tỉnh dậy
  /*
   * Máy ngủ sâu (Doze) thì bộ đếm giờ của trang gần như dừng: Zalo Web không gửi được tín hiệu giữ kết nối, server
   * ngắt WebSocket, và Zalo chỉ thử nối lại theo nhịp chờ tăng dần của nó — nhiều khi tới lúc mở app vẫn còn
   * "không có kết nối mạng" và tin đến trong lúc đó không có thông báo. App báo "đã tỉnh" (bật màn hình, mở khoá,
   * hết Doze, có mạng lại, mở app): nếu không còn WebSocket nào của Zalo đang mở thì giục Zalo nối lại (sự kiện
   * online), sau một lúc vẫn chưa nối được thì tải lại trang. Theo dõi bằng cách bọc WebSocket (chỉ đếm, không đọc dữ liệu).
   */
  safe(function () {
    if (window.opener || window.top !== window || !window.WebSocket) return;
    var NativeWS = window.WebSocket;
    var socks = [], lastMsg = 0, lastOpen = 0, wsSeq = 0;
    function isZalo(url) { return /zalo\.me|zaloapp\.com|zadn\.vn/i.test(String(url)); }
    function ago(t) { return t ? Math.round((Date.now() - t) / 1000) + 's' : '-'; }
    function pageState() { return (isHidden(document) ? 'hidden' : 'visible') + (navigator.onLine ? '' : ' offline'); }
    /*
     * Nhật ký kết nối: mỗi WebSocket của Zalo có số thứ tự; ghi mở / đóng (mã, lý do, sống bao lâu, lần nhận / gửi cuối
     * cách bao lâu) và khoảng lặng dài giữa hai lần nhận / gửi — Zalo gửi nhịp giữ kết nối đều đặn, lặng lâu nghĩa là
     * bộ đếm giờ của trang bị dừng (app ở nền) hoặc mạng bị chặn. Chỉ đếm, không đọc dữ liệu.
     */
    function Wrapped(url, protocols) {
      var s = protocols === undefined ? new NativeWS(url) : new NativeWS(url, protocols);
      if (isZalo(url)) {
        socks.push(s);
        if (socks.length > 20) socks = socks.filter(function (x) { return x.readyState < 2; });
        var id = ++wsSeq, born = Date.now(), opened = 0, rx = 0, tx = 0, nRx = 0, nTx = 0, host = '';
        try { host = new URL(String(url), location.href).host; } catch (e) { /* ignore */ }
        connLog('ws#' + id + ' connecting ' + host + ' (' + pageState() + ')');
        var send = s.send;
        s.send = function () {
          var now = Date.now();
          if (tx && now - tx > 90000) connLog('ws#' + id + ' send after ' + Math.round((now - tx) / 1000) + 's silence (' + pageState() + ')');
          tx = now; nTx++;
          var a0 = arguments[0];
          if (api.callTapSend) safe(function () { api.callTapSend(id, a0); });
          return send.apply(s, arguments);
        };
        s.addEventListener('open', function () {
          lastOpen = lastMsg = opened = Date.now(); diag('ws open');
          connLog('ws#' + id + ' open after ' + (opened - born) + 'ms (' + pageState() + ')');
          connChanged();
        });
        s.addEventListener('message', function (ev) {
          if (api.callTap) safe(function () { api.callTap(id, ev.data); });
          var now = Date.now();
          if (rx && now - rx > 90000) connLog('ws#' + id + ' recv after ' + Math.round((now - rx) / 1000) + 's silence (' + pageState() + ')');
          lastMsg = rx = now; nRx++;
        });
        s.addEventListener('error', function () { connLog('ws#' + id + ' error (' + pageState() + ')'); });
        s.addEventListener('close', function (e) {
          lastClose = Date.now(); diag('ws closed (' + e.code + ')');
          connLog('ws#' + id + ' closed code=' + e.code + (e.reason ? ' reason=' + String(e.reason).slice(0, 60) : '') +
            ' clean=' + e.wasClean + ' lived=' + (opened ? ago(opened) : 'never opened') + ' lastRecv=' + ago(rx) +
            ' lastSend=' + ago(tx) + ' frames=' + nRx + '/' + nTx + ' (' + pageState() + ')');
          connChanged();
        });
        connChanged();
      }
      return s;
    }
    var lastClose = 0, startAt = Date.now(), nudgeAt = 0;

    /**
     * Trạng thái kết nối máy chủ Zalo cho nhãn trên thanh trên (mục 8, #zd-head .zd-conn): 'on' có WebSocket đang mở, 'wait'
     * đang mở WebSocket / vừa mất (Zalo đang tự nối lại) / vừa giục nối lại / trang mới tải, 'off' không có mạng hoặc
     * mất kết nối đã lâu mà Zalo chưa nối lại được.
     */
    api.connState = function () {
      if (openCount() > 0) return 'on';
      if (!navigator.onLine) return 'off';
      if (socks.some(function (x) { return x.readyState === 0; })) return 'wait';
      var now = Date.now();
      if (now - startAt < 30000 || now - lastClose < 8000 || now - nudgeAt < 20000) return 'wait';
      return 'off';
    };
    var sentConn = '', loggedConn = '';
    function connChanged() {
      setTimeout(function () {
        safe(function () {
          dispatchEvent(new Event('zd-conn'));
          var s = api.connState();
          if (s !== loggedConn) { connLog('state ' + (loggedConn || '?') + ' -> ' + s); loggedConn = s; }
          if (s !== sentConn && window.ZaloBridge) { sentConn = s; bridge.connState(s); } // thông báo chạy nền
        });
      }, 0);
    }
    /** App vừa đánh thức trang (hẹn giờ chạy nền): báo lại trạng thái dù không đổi, thông báo luôn đúng. */
    api.connReport = function () { sentConn = ''; connChanged(); };
    addEventListener('online', function () { connLog('browser online'); connChanged(); });
    addEventListener('offline', function () { connLog('browser offline'); connChanged(); });
    document.addEventListener('visibilitychange', function () { connLog('page ' + pageState()); });
    document.addEventListener('freeze', function () { connLog('page frozen by webview'); });
    document.addEventListener('resume', function () { connLog('page resumed'); });
    var tick = Date.now();
    setInterval(function () {
      // Nhịp 3 s đến trễ: bộ đếm giờ của trang bị dừng / bóp (app ở nền, CPU ngủ) — Zalo cũng không gửi được nhịp giữ kết nối.
      var now = Date.now();
      if (now - tick > 15000) connLog('page timers stalled ' + Math.round((now - tick) / 1000) + 's (' + pageState() + ', open=' + openCount() + ')');
      tick = now;
      connChanged(); // các mốc thời gian ('wait' hết hạn) không tự phát sự kiện
    }, 3000);
    Wrapped.prototype = NativeWS.prototype;
    ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (k) { Wrapped[k] = NativeWS[k]; });
    Wrapped.toString = function () { return 'function WebSocket() { [native code] }'; };
    window.WebSocket = Wrapped;

    function openCount() {
      socks = socks.filter(function (x) { return x.readyState < 2; });
      return socks.filter(function (x) { return x.readyState === 1; }).length;
    }
    /** WebSocket của Zalo đang mở (mục 19 dùng để kéo hàng đợi cuộc gọi). */
    api.openSockets = function () {
      return socks.filter(function (x) { return x.readyState === 1; });
    };
    api.wsInfo = function () {
      return JSON.stringify({ open: openCount(), lastMsgAgo: lastMsg ? Math.round((Date.now() - lastMsg) / 1000) : -1,
        lastOpenAgo: lastOpen ? Math.round((Date.now() - lastOpen) / 1000) : -1 });
    };

    var checking = false, lastReload = 0;
    api.wake = function (reason) {
      if (checking || !document.getElementById('sidebarNav')) return; // chưa đăng nhập: để Zalo tự lo
      var n = openCount();
      connLog('wake (' + reason + '): open=' + n + ' lastRecv=' + ago(lastMsg) + ' (' + pageState() + ')');
      if (n > 0) return;
      // Bị đóng băng lâu (không nhận gì > 3 phút) rồi mất kết nối: phiên của Zalo đã cũ, nối lại thường không được
      // (nhật ký: chờ 15 s rồi vẫn phải tải lại) — tải lại trang ngay.
      if (lastMsg && Date.now() - lastMsg > 180000 && navigator.onLine && Date.now() - lastReload > 120000) {
        lastReload = Date.now();
        diag('wake (' + reason + '): disconnected after long pause, reloading page');
        connLog('disconnected after ' + ago(lastMsg) + ' silence, reloading page now');
        location.reload();
        return;
      }
      checking = true;
      nudgeAt = Date.now();
      connChanged();
      diag('wake (' + reason + '): no zalo connection, nudging reconnect');
      safe(function () { dispatchEvent(new Event('online')); });
      setTimeout(function () {
        checking = false;
        if (openCount() > 0) { diag('reconnected after wake'); connLog('reconnected after wake'); return; }
        if (!navigator.onLine || Date.now() - lastReload < 120000) return; // mất mạng thật / vừa tải lại
        lastReload = Date.now();
        diag('still disconnected after wake, reloading page');
        connLog('still disconnected 15s after wake, reloading page');
        location.reload();
      }, 15000); // thử trên máy ảo: Zalo tự nối lại ~10 s sau khi có mạng, đừng tải lại khi nó sắp nối được
    };
  });

  // ------------------------------------------------------------------ 12. Danh sách chat native
  /*
   * App vẽ danh sách chat bằng giao diện Android (NativeChatList), nằm dưới WebView. Ở đây:
   *   - Lấy dữ liệu đã giải mã từ các dịch vụ của Zalo (tìm theo tên export, không theo mã module): hội thoại, tin
   *     xem trước, số chưa đọc, ghim, tắt thông báo, tên + ảnh bạn bè / nhóm. Gửi sang app (bridge.convList) mỗi khi
   *     đổi; app lưu lại để lần mở sau có ngay.
   *   - Khi app đã vẽ xong (api.nativeReady) và đang ở danh sách chat: ẩn danh sách web, làm trong suốt nền dưới thanh
   *     trên để thấy danh sách native, báo vị trí thanh trên + dock (bridge.nativeLayout) để app biết chạm nào là của
   *     danh sách native.
   *   - api.openConv: mở hội thoại bằng ConversationController của Zalo (như bấm một dòng).
   */
  safe(function () {
    if (!mobile || !cfg.nativeList || window.opener || window.top !== window) return;
    var Z = null; // các dịch vụ của Zalo, tìm một lần
    function findZalo() {
      if (Z) return Z;
      var jp = window.webpackJsonp, r = null;
      if (!jp || typeof jp.push !== 'function') return null;
      var id = 'zdnl' + Date.now(), mods = {};
      mods[id] = function (m, e, req) { r = req; };
      jp.push([[id], mods, [[id]]]);
      if (!r || !r.c) return null;
      var found = {};
      for (var k in r.c) {
        var ex = r.c[k] && r.c[k].exports;
        if (!ex || typeof ex !== 'object') continue;
        if (!found.container && ex.ModuleContainer && typeof ex.ModuleContainer.resolve === 'function') found.container = ex.ModuleContainer;
        if (!found.tokens && ex.PreviewDataManager && ex.UnreadDataManager) found.tokens = ex;
        if (!found.convCtl && ex.ConversationController && ex.ConversationController.service) found.convCtl = ex.ConversationController;
        var d = ex.default;
        if (!found.storage && d && typeof d.getConversations === 'function' && typeof d.getFriends === 'function') found.storage = d;
        if (!found.i18n && d && typeof d.str === 'function' && typeof d.currentLanguage === 'function') found.i18n = d;
        // Tên gợi nhớ (người dùng tự đặt cho bạn bè, vd. "Bố"): Zalo hiện tên này thay cho tên Zalo ở mọi nơi.
        if (!found.alias && d && typeof d.getAliasName === 'function' && typeof d.getAllAlias === 'function') found.alias = d;
        // Hồ sơ mọi tài khoản đã gặp, cả người lạ và Official Account (không có trong getFriends, tin cuối không có dName).
        if (!found.profile && d && typeof d.getProfileFriendByIdSync === 'function') found.profile = d;
        // Hàm Zalo dùng để ra dòng tin cuối ở danh sách (chữ, "Sticker", tên file, tin hệ thống...).
        if (!found.fmt) {
          for (var n in ex) {
            if (typeof ex[n] !== 'function') continue;
            var src = Function.prototype.toString.call(ex[n]);
            if (src.length < 20000 && src.indexOf('computedIcon') >= 0 && src.indexOf('isSystemicMessage') >= 0) { found.fmt = ex[n]; break; }
          }
        }
      }
      if (!found.container || !found.tokens || !found.storage) return null;
      var C = found.container, T = found.tokens;
      function res(t) { try { return C.resolve(t); } catch (e) { return null; } }
      Z = { st: found.storage, preview: res(T.PreviewDataManager), unread: res(T.UnreadDataManager),
        pin: res(T.PinDataManager), mute: res(T.MuteDataManager), conv: found.convCtl ? res(found.convCtl) : null,
        info: T.ConvInfoDataManager ? res(T.ConvInfoDataManager) : null, // tên / ảnh cả người lạ (không trong danh bạ)
        fmt: found.fmt, i18n: found.i18n, alias: found.alias || null, profile: found.profile || null };
      diag('native list: zalo services ' + (Z.preview && Z.unread ? 'found' : 'partial') + (Z.fmt ? '' : ', no preview formatter'));
      return Z;
    }

    /** Một dòng tin xem trước, như Zalo Web: dùng hàm định dạng của Zalo; không có thì đoán theo loại. */
    /** Bảng tên gợi nhớ (mã người → tên), nạp lại mỗi lần lấy lại danh bạ. Dạng giá trị tùy bản Zalo: chuỗi / đối tượng. */
    var aliasMap = null, aliasSvc = null;
    function loadAliases(z) {
      if (!z.alias) return;
      aliasSvc = z.alias;
      safe(function () {
        var all = z.alias.getAllAlias();
        if (all && typeof all.then === 'function') all.then(function (m) { aliasMap = m || null; }, function () { /* ignore */ });
        else aliasMap = all || null;
      });
    }
    function aliasOf(uid) {
      if (!uid) return '';
      var v = aliasMap && (typeof aliasMap.get === 'function' ? aliasMap.get(String(uid)) : aliasMap[uid]);
      if (!v && aliasSvc) v = safe(function () { return aliasSvc.getAliasName(String(uid)); }); // bảng tổng chưa nạp: hỏi từng người
      if (v && typeof v === 'object') v = v.alias || v.aliasName || v.name || v.displayName || '';
      return typeof v === 'string' ? v.trim() : '';
    }
    function previewText(p) {
      if (Z.fmt) {
        try {
          var f = Z.fmt(p, { isSyncing: false, isSyncEnabled: false }) || {};
          var s = f.message || '';
          if (f.isSystemicMessage && Z.i18n) {
            s = Z.i18n.str(s);
            (f.systemicMessageArgs || []).forEach(function (a, i) { s = s.split('%' + (i + 1) + '$s').join(a); });
          }
          if (s) return { t: zEmoji(String(s).replace(/\s+/g, ' ').slice(0, 200)), ic: f.icon || '', miss: !!f.isMisscall };
        } catch (e) { /* dùng cách đoán bên dưới */ }
      }
      return { t: zEmoji(guessText(p)), ic: '', miss: false };
    }
    function guessText(p) {
      var m = p.message, t = '';
      if (typeof m === 'string') t = m;
      else if (m && typeof m === 'object') {
        if (m.catId || m.stickerId) t = '[Sticker]';
        else if (m.title) t = m.title;
        else if (m.thumb || /\.(jpe?g|png|gif|webp)/i.test(m.href || '')) t = '[Hình ảnh]';
        else if (m.href) t = '[Liên kết]';
      }
      if (!t) t = '[Tin nhắn]';
      return t.replace(/\s+/g, ' ').slice(0, 200);
    }

    var pageStart = Date.now();
    var lastSent = '', building = false, names = {}, namesAt = 0; // tên + ảnh bạn bè / nhóm, lấy lại tối đa mỗi 60 s
    /**
     * Mã các hội thoại danh sách web đang hiện (đã lọc theo Ưu tiên / Khác, Chưa đọc, Phân loại...), theo thứ tự của
     * Zalo: prop "conversations" của component cha #conversationListId (danh sách ảo hóa chỉ vẽ vài dòng trong DOM).
     */
    function webOrder() {
      var l = document.getElementById('conversationListId');
      var fk = l && Object.keys(l).filter(function (k) { return k.indexOf('__react') === 0; })[0];
      for (var f = fk && l[fk], i = 0; f && i < 6; f = f.return, i++) {
        var c = f.memoizedProps && f.memoizedProps.conversations;
        if (Array.isArray(c)) return c.map(String);
      }
      // Tab lọc không có hội thoại nào: Zalo bỏ hẳn danh sách, chỉ còn thanh lọc + màn trống.
      if (!l && document.querySelector('#sidebarNav .msg-filters-bar')) return [];
      return null;
    }
    function buildAndSend() {
      if (building) return;
      var z = findZalo();
      if (!z) return;
      building = true;
      var me = '';
      try { me = String(z.st.getUserId ? z.st.getUserId() : ''); } catch (e) { /* ignore */ }
      var fresh = Date.now() - namesAt > 60000;
      Promise.all([z.st.getConversations(), fresh ? z.st.getFriends() : null, fresh ? z.st.getGroups() : null]).then(function (all) {
        var convs = all[0] || [];
        // getConversations chỉ có hội thoại 1-1, nhóm nằm ở ConvInfoDataManager.
        try {
          var hasConv = {};
          convs.forEach(function (c) { hasConv[c.userId] = true; });
          convs = convs.concat((z.info && z.info.getAllConvSync ? z.info.getAllConvSync() : []).filter(function (c) { return c && !hasConv[c.userId]; }));
        } catch (e) { /* ignore */ }
        // Trang vừa tải: danh bạ / nhóm có thể chưa nạp (rỗng) — giữ tên cũ, lần dựng sau lấy lại ngay.
        if (fresh && ((all[1] || []).length || (all[2] || []).length)) {
          namesAt = Date.now();
          loadAliases(z);
          (all[1] || []).forEach(function (f) { names[f.userId] = { n: f.displayName || f.zaloName, a: f.avatar }; });
          (all[2] || []).forEach(function (g) {
            // Nhóm không có ảnh riêng: Zalo ghép ảnh vài thành viên + tổng số người.
            var ms = (Array.isArray(g.topMember) ? g.topMember : []).map(function (t) { return t && t.avatar; })
              .filter(Boolean).slice(0, 3);
            names[g.userId] = { n: g.displayName, a: g.avatar, ms: ms, tm: g.totalMember || 0 };
          });
        }
        var previews = {}, unreads = {}, pinned = {};
        (z.preview && z.preview.getAllPreviewsSync ? z.preview.getAllPreviewsSync() : []).forEach(function (p) { previews[p.convId] = p; });
        (z.unread && z.unread.getAllUnreadsSync ? z.unread.getAllUnreadsSync() : []).forEach(function (u) { unreads[u.convId] = u; });
        try {
          (z.pin && z.pin.getAllPinnedConversationsSync ? z.pin.getAllPinnedConversationsSync() : []).forEach(function (p, i) {
            pinned[typeof p === 'string' ? p : (p.convId || p.id || p.userId)] = 1000 - i;
          });
        } catch (e) { /* ignore */ }
        // Hội thoại ảo của Zalo (vd. "stranger_box" — hộp tin người lạ, mã không phải số): web chỉ hiện khi có tin.
        convs = convs.filter(function (c) { return c.isGroup || /^\d+$/.test(c.userId) || previews[c.userId]; });
        var list = convs.map(function (c) {
          var id = c.userId, p = previews[id], u = unreads[id] || {}, info = names[id];
          if (!info || !info.n) {
            // Không có trong danh bạ / nhóm (người lạ, Official Account...): hồ sơ của Zalo vẫn có tên + ảnh.
            var ci = null;
            try { ci = z.profile ? z.profile.getProfileFriendByIdSync(id) : null; } catch (e) { /* ignore */ }
            try { ci = ci || (z.info && z.info.getConvByIdSync ? z.info.getConvByIdSync(id) : null); } catch (e) { /* ignore */ }
            if (ci && (ci.displayName || ci.zaloName)) {
              info = { n: ci.displayName || ci.zaloName, a: ci.avatar || (info && info.a) || '', ms: info && info.ms, tm: info && info.tm };
              names[id] = info;
            }
          }
          info = info || {};
          var name = (!c.isGroup && aliasOf(id)) || info.n || (id === me ? 'My Documents' : '') || (p && !c.isGroup && p.fromUid === id ? p.dName : '') || id;
          if (name === 'Truyền File') name = 'My Documents'; // tên nội bộ của My Documents, web hiện "My Documents"
          var text = 'Chưa có tin nhắn', icon = '', miss = false;
          if (p) {
            var pv = previewText(p);
            text = pv.t;
            icon = pv.ic;
            miss = pv.miss;
            if (p.fromUid === me || p.fromUid === '0') text = 'Bạn: ' + text;
            else if (c.isGroup && (p.dName || p.fromUid)) {
              // Người gửi trong nhóm: tên gợi nhớ / tên trong danh bạ như Zalo hiện trong chat, không phải tên Zalo gốc (dName).
              var fr = names[p.fromUid];
              var who = aliasOf(p.fromUid) || (fr && !fr.ms && fr.n) || p.dName;
              if (!who) { // không phải bạn bè, tin cuối không có dName
                try {
                  var pr = z.profile ? z.profile.getProfileFriendByIdSync(p.fromUid) : null;
                  who = pr && (pr.displayName || pr.zaloName);
                } catch (e) { /* ignore */ }
              }
              if (who) text = who + ': ' + text;
            }
          }
          var muted = false;
          try { muted = !!(z.mute && z.mute.isMuted && z.mute.isMuted(id)); } catch (e) { /* ignore */ }
          return { id: id, g: !!c.isGroup, n: name, a: info.a || '', ms: info.a ? [] : info.ms || [], tm: info.tm || 0,
            t: p ? +p.messageTime || 0 : 0, m: text, ic: icon, miss: miss,
            u: (u.smsUnreadCount || 0) + (u.strangerUnreadCount || 0), at: u.mentionUnreadCount || 0,
            ud: u.smsUnreadNotCount || 0, p: pinned[id] || 0, mu: muted, me: id === me };
        });
        var order = webOrder();
        if (order) {
          // Đúng tab lọc đang chọn trên thanh của Zalo, theo thứ tự của Zalo.
          var byId = {};
          list.forEach(function (it) { byId[it.id] = it; });
          list = order.map(function (id) { return byId[id]; }).filter(Boolean);
        } else list.sort(function (a, b) { return (b.p - a.p) || (b.t - a.t); });
        document.documentElement.classList.toggle('zd-nempty', !!order && !order.length);
        // Trang vừa tải (lại): Zalo đã có danh sách hội thoại nhưng chưa nạp tin cuối / danh bạ — toàn "Chưa có tin
        // nhắn", tên là mã số, sai thứ tự. Đừng gửi bản đó đè lên danh sách app đang hiện; chờ nạp xong (tối đa 25 s
        // kể từ khi trang mở, phòng tài khoản ít hội thoại có tin thật).
        // "Có tên" = đã có trong danh bạ / nhóm (có cả ảnh); tên lấy tạm từ tin cuối thì chưa tính.
        var withMsg = 0, named = 0;
        list.forEach(function (it) { if (it.t) withMsg++; if (names[it.id] || it.me) named++; });
        if (list.length && (!withMsg || !me || named < list.length * 0.6) && Date.now() - pageStart < 25000) {
          scheduleBuild(800);
          return;
        }
        var json = JSON.stringify(list);
        if (json !== lastSent) {
          lastSent = json;
          bridge.convList(json);
        }
      }).catch(function (e) { log('native list build failed: ' + e); }).then(function () { building = false; });
    }
    var buildTimer = 0;
    function scheduleBuild(ms) {
      clearTimeout(buildTimer);
      buildTimer = setTimeout(function () { safe(buildAndSend); }, ms);
    }
    api.requestConvList = function () { lastSent = ''; scheduleBuild(0); };
    api.openConv = function (id) {
      var z = findZalo();
      if (z && z.conv && z.conv.openConversation) {
        z.conv.openConversation(String(id)).then(function (ok) { if (!ok) diag('native list: open conv refused'); });
        setTimeout(function () { if (M.sync) M.sync(); }, 150);
      }
    };

    /*
     * Giữ lâu một dòng native: mở đúng menu thao tác của Zalo (Ghim, Phân loại, Đánh dấu chưa đọc, Tắt thông báo, Ẩn,
     * Xóa...) bằng nút "…" của dòng web tương ứng (danh sách web vẫn dựng ngầm, ảo hóa: cuộn tới dòng đó cho nó vẽ ra).
     * idx: vị trí dòng trong danh sách native (cùng thứ tự với Zalo), y / h: mép trên + chiều cao dòng (px CSS), shot:
     * ảnh chụp dòng (data URL) để dựng thẻ nổi kiểu "focus" của app Zalo.
     */
    var focus = null;
    function focusStart(y, h, shot) {
      focusEnd(true);
      var root = document.documentElement, bg = getComputedStyle(root).getPropertyValue('--zd-listbg');
      var m = /(\d+),\s*(\d+),\s*(\d+)/.exec(bg || ''), dark = m && (+m[1] * 299 + +m[2] * 587 + +m[3] * 114) / 1000 < 110;
      root.style.setProperty('--zd-elev', dark ? 'rgba(255,255,255,.09)' : 'transparent');      var s = document.createElement('div');
      s.id = 'zd-focus-scrim';
      var c = document.createElement('div');
      c.id = 'zd-focus';
      c.style.top = y + 'px';
      c.style.height = h + 'px';
      c.innerHTML = '<div class="bg"></div>' + (shot ? '<img alt="">' : '');
      if (shot) c.lastChild.src = shot;
      document.body.appendChild(s);
      document.body.appendChild(c);
      c.getBoundingClientRect(); // trạng thái đầu (dòng nằm phẳng) trước khi chuyển sang thẻ nổi
      s.classList.add('on');
      c.classList.add('on');
      focus = { s: s, c: c, y: y, h: h };
    }
    /** Menu đóng (mục 13 gọi khi Zalo gỡ menu): thẻ hạ về thành dòng, nền phủ mờ đi. */
    function focusEnd(now) {
      if (!focus) return;
      var f = focus;
      focus = null;
      if (now) { f.s.remove(); f.c.remove(); return; }
      f.s.classList.remove('on');
      f.c.classList.remove('on');
      f.c.style.top = f.y + 'px';
      setTimeout(function () { f.s.remove(); f.c.remove(); }, 280);
    }
    api.convFocusEnd = function () { safe(function () { focusEnd(false); }); };

    /**
     * Controller danh sách chat của Zalo (ConvListController, đã gắn menu) lấy từ props của một dòng web bất kỳ đang
     * vẽ; showConvActionMenu(sự kiện, hội thoại) mở menu thao tác cho hội thoại nào cũng được — không phải cuộn danh
     * sách web tới dòng đó rồi bấm nút "…" (dễ hỏng theo thời điểm: Zalo đóng menu khi danh sách đang cuộn).
     */
    function convController() {
      var r = document.querySelector('#conversationList .msg-item');
      var fk = r && Object.keys(r).filter(function (k) { return k.indexOf('__react') === 0; })[0];
      for (var f = fk && r[fk], i = 0; f && i < 8; f = f.return, i++) {
        var p = f.memoizedProps;
        if (p && p.convlistController && typeof p.convlistController.showConvActionMenu === 'function') {
          return { ctl: p.convlistController, row: r };
        }
      }
      return null;
    }

    api.convMenu = function (id, idx, y, h, shot) {
      id = String(id);
      y = +y || 0;
      h = +h || 78;
      safe(function () { focusStart(y, h, shot); });
      var z = findZalo(), cc = convController(), conv = null;
      try { conv = z && z.info && z.info.getConvByIdSync ? z.info.getConvByIdSync(id) : null; } catch (e) { /* ignore */ }
      if (cc && conv) {
        var b = cc.row.getBoundingClientRect();
        cc.ctl.showConvActionMenu({ clientX: b.right - 24, clientY: y + h / 2, pageX: b.right - 24, pageY: y + h / 2,
          target: cc.row, currentTarget: cc.row, preventDefault: function () {}, stopPropagation: function () {},
          nativeEvent: {} }, conv);
        return placeMenu(y, 0);
      }
      // Dự phòng: cuộn danh sách web tới dòng đó và bấm nút "…" của nó.
      var sc = document.querySelector('#conversationListId .virtualized-scroll') ||
        document.querySelector('#conversationListId [class*=ReactVirtualized__Grid]');
      var tries = 0;
      function rowOf() { return document.querySelector('#conversationList .msg-item[anim-data-id="' + id.replace(/"/g, '') + '"]'); }
      function attempt() {
        var r = rowOf();
        if (!r && sc && tries < 40) {
          // Lần đầu nhảy thẳng tới vị trí ước tính, sau đó dò dần từ trên xuống.
          var h = 78;
          sc.scrollTop = tries === 0 ? Math.max(0, (idx | 0) * h - h) : (tries - 1) * h * 4;
          tries++;
          return void requestAnimationFrame(function () { setTimeout(function () { safe(attempt); }, 30); });
        }
        var more = r && r.querySelector('.conv-item-title__more .z--btn--v2, .conv-item-title__more [icon]');
        if (!more) { diag('native list: conv menu row not found'); focusEnd(false); return; }
        // Vừa cuộn danh sách web tới dòng này: sự kiện cuộn đến muộn và Zalo đóng menu khi danh sách cuộn — chờ yên
        // rồi mới bấm.
        if (tries > 0 && !settled) {
          settled = true;
          return void setTimeout(function () { safe(attempt); }, 220);
        }
        click(more);
      }
      var settled = false;
      function click(more) {
        var b = more.getBoundingClientRect();
        ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function (t) {
          more.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window,
            clientX: b.left + b.width / 2, clientY: b.top + b.height / 2 }));
        });
        placeMenu(y, 0);
      }
      function placeMenu(top, n) {
        var pop = [].filter.call(document.querySelectorAll('body > .popover-v3'), function (p) {
          return !!p.querySelector('.zmenu-body, .zmenu-item') && !p.classList.contains('zd-acct-pop');
        }).pop();
        if (!pop) { if (n < 20) setTimeout(function () { placeMenu(top, n + 1); }, 30); else focusEnd(false); return; }
        pop.classList.add('zd-conv-pop');
        // Menu ngay dưới thẻ; không đủ chỗ thì lên trên thẻ; cả hai đều không đủ thì đẩy thẻ lên cho menu vừa bên
        // dưới (như app Zalo). Nền phủ che cả dock nên dùng hết chiều cao màn hình.
        var vh = window.innerHeight, ph = pop.offsetHeight, gap = 6, card = top, t;
        if (top + h + gap + ph <= vh - 12) t = top + h + gap;
        else if (top - gap - ph >= 12) t = top - gap - ph;
        else {
          card = Math.max(12, vh - 12 - ph - gap - h);
          t = card + h + gap;
        }
        if (focus && card !== top) focus.c.style.top = card + 'px';
        pop.style.setProperty('top', Math.round(t) + 'px', 'important');
        // Góc phóng của hiệu ứng mở (mục 13): mép menu sát thẻ.
        pop.style.setProperty('--zd-po', (t < card ? 'bottom' : 'top') + ' left');
        if (api.trackMenus) api.trackMenus();
        if (M.sync) M.sync();
        safe(syncNative);
      }
      safe(attempt);
    };

    /*
     * Bật/tắt chế độ native: chỉ khi app đã vẽ danh sách (nativeOk), đang ở tab Tin nhắn, không mở chat, không tìm
     * kiếm. Báo vị trí thanh trên (#contact-search) và dock cho app (px CSS, theo khung WebView).
     */
    var nativeOk = false, lastLayout = '';
    api.nativeReady = function (ok) { nativeOk = !!ok; syncNative(); };
    function syncNative() {
      var root = document.documentElement;
      var msgTab = document.querySelector('#main-tab [data-translate-title=STR_TAB_MESSAGE]');
      // Giữ chế độ native cả khi đang mở chat / hộp thoại (chúng che lên trên): tắt đi thì danh sách web hiện ra phía
      // sau, lúc đóng chat trượt ra sẽ lộ danh sách cũ một nhịp. Chỉ nhường chạm (pass) khi không có gì che.
      var active = nativeOk && M.ok && !M.cdetail && !root.classList.contains('zd-searching') &&
        !!msgTab && msgTab.classList.contains('selected');
      var pass = active && !M.chat && !document.querySelector('.zl-modal__dialog, body > .popover-v3, .media-viewer');
      if (active !== root.classList.contains('zd-native')) {
        if (active) {
          // Nền danh sách của Zalo (sáng/tối): giữ cho thanh trên, app tô cùng màu cho danh sách native.
          var col = document.querySelector('#sidebarNav > :not(#main-tab)');
          if (col) root.style.setProperty('--zd-listbg', getComputedStyle(col).backgroundColor);
        }
        root.classList.toggle('zd-native', active);
      }
      // Màu theo danh sách web (vẫn dựng ngầm): nền dòng, chữ tên, chữ phụ — đổi giao diện sáng/tối là theo ngay.
      var row = document.querySelector('#conversationList .msg-item'), nm = row && row.querySelector('.conv-item-title__name');
      var sub = row && row.querySelector('.preview-time');
      if (row && active) {
        var rb = getComputedStyle(row).backgroundColor;
        if (rb && rb !== 'rgba(0, 0, 0, 0)' && rb !== root.style.getPropertyValue('--zd-listbg')) root.style.setProperty('--zd-listbg', rb);
      }
      var cs = document.querySelector('#sidebarNav .msg-filters-bar'), tab = document.getElementById('main-tab');
      if (!cs || !cs.offsetHeight) cs = document.getElementById('contact-search');
      var hb = cs && active ? Math.max(0, Math.round(cs.getBoundingClientRect().bottom)) : 0;
      var d = tab && active ? tab.getBoundingClientRect() : null;
      // dh: dock đang trượt xuống ẩn (html.zd-dockhide) — app cho lớp kính mờ dưới dock trượt theo cùng lúc.
      var lay = JSON.stringify({ on: active, pass: pass, top: hb, dock: d ? [Math.round(d.left), Math.round(d.top), Math.round(d.right),
        Math.round(d.bottom)] : null, dh: root.classList.contains('zd-dockhide'), tabs: filterTabs(), cat: catLabel(), bg: getComputedStyle(root).getPropertyValue('--zd-listbg') || '',
        fg: nm ? getComputedStyle(nm).color : '', fg2: sub ? getComputedStyle(sub).color : '', me: meInfo() });
      if (lay !== lastLayout) { lastLayout = lay; bridge.nativeLayout(lay); }
    }
    api.syncNative = function () { safe(syncNative); };
    /**
     * Các tab của thanh lọc (Ưu tiên / Khác hoặc Tất cả / Chưa đọc) + tab đang chọn: [[tên...], chỉ số]. App vẽ lại
     * lúc mở app khi trang còn tải, và trượt trang danh sách khi đổi tab (chỉ số tăng: trượt sang trái).
     */
    function filterTabs() {
      var bar = document.querySelector('#sidebarNav .msg-filters-bar');
      if (!bar) return null;
      var t = [].slice.call(bar.querySelectorAll('.tab-item'));
      if (!t.length) {
        // Bản Zalo khác (Ưu tiên / Khác) có thể không dùng .tab-item: lấy các nhãn chữ bên trái thanh, trừ nút Phân
        // loại và nút "…".
        t = [].filter.call(bar.querySelectorAll('*'), function (e) {
          return !e.children.length && e.textContent.trim() && e.offsetWidth &&
            !e.closest('.filter-preview-v2, .label-filter-preview, .z--btn--v2');
        });
      }
      if (!t.length) return null;
      var sel = 0;
      t.forEach(function (e, i) {
        if (e.classList.contains('selected') || e.closest('.selected, [class*=active]') && bar.contains(e.closest('.selected, [class*=active]'))) sel = i;
      });
      return [t.map(function (e) { return e.textContent.trim(); }), sel];
    }
    /** Chữ của nút Phân loại (đổi thành tên thẻ đang lọc khi chọn thẻ). */
    function catLabel() {
      var e = document.querySelector('#sidebarNav .msg-filters-bar .filter-preview-v2');
      return e ? e.textContent.trim() : '';
    }

    /**
     * Thanh trên + thanh lọc của app (NativeChatList.Header) thay cho thanh của trang (ẩn trong chế độ native): chạm nút
     * nào ở thanh app thì bấm hộ nút đó ở đây — menu tài khoản, tìm kiếm, thêm bạn, tạo nhóm, tab, Phân loại, "…".
     */
    api.headTap = function (what, i) {
      var bar = '#sidebarNav .msg-filters-bar ';
      var el = what === 'head' ? document.getElementById('zd-head')
        : what === 'search' ? document.getElementById('contact-search-input')
        : what === 'add' ? document.querySelectorAll('#contact-search > .z--btn--v2')[0]
        : what === 'group' ? document.querySelectorAll('#contact-search > .z--btn--v2')[1]
        : what === 'tab' ? document.querySelectorAll(bar + '.tab-item')[i | 0]
        : what === 'cat' ? document.querySelector(bar + '.filter-preview-v2')
        : what === 'more' ? document.querySelector(bar + '.z--btn--v2') : null;
      if (!el) return;
      if (what === 'search') { el.focus(); el.click(); return; }
      if (what === 'head') { el.click(); return; }
      var b = el.getBoundingClientRect(), x = b.left + b.width / 2, y = b.top + b.height / 2;
      ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function (t) {
        el.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y }));
      });
    };
    /** Tên + ảnh tài khoản trên thanh trên (#zd-head): app vẽ lại thanh này lúc mở app khi trang còn đang tải. */
    function meInfo() {
      var h = document.getElementById('zd-head'), n = h && h.querySelector('.zd-head-name'), img = h && h.querySelector('img');
      var src = img && img.src && img.src.indexOf('http') === 0 ? img.src : '';
      if (!src && h) { // có bản Zalo vẽ ảnh bằng background-image
        var ch = h.querySelector('.zd-head-av .a-child'), m = ch && /url\(["']?(https?:[^"')]+)/.exec(ch.style.backgroundImage || '');
        if (m) src = m[1];
      }
      return [n ? n.textContent : '', src];
    }

    // Zalo cập nhật danh sách web (kể cả khi đang ẩn) mỗi khi có tin mới, đọc, ghim...: dựng lại danh sách native.
    var listObs = new MutationObserver(function () { scheduleBuild(300); }), observedList = null;
    function watchList() {
      var l = document.getElementById('conversationListId');
      if (!l || l === observedList) return;
      observedList = l;
      scheduleBuild(150); // danh sách mới (đổi tab lọc, vừa tải xong): dựng lại theo nó
      listObs.disconnect();
      listObs.observe(l, { childList: true, subtree: true, characterData: true });
    }
    function start() {
      new MutationObserver(function () { safe(watchList); safe(syncNative); })
        .observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
      document.addEventListener('transitionend', function () { safe(syncNative); }, true);
      // Đổi tab lọc (Ưu tiên / Khác...): dựng lại ngay theo danh sách web mới.
      document.addEventListener('click', function (e) {
        if (e.target && e.target.closest && e.target.closest('.msg-filters-bar, .popover-v3')) {
          // Ngay sau khi Zalo xử lý cú bấm (bắt ở pha capture, nên chờ một nhịp): app trượt trang cùng lúc với tab.
          scheduleBuild(0);
          setTimeout(function () { scheduleBuild(0); }, 120);
          setTimeout(function () { scheduleBuild(0); }, 700);
        }
      }, true);
      setInterval(function () { if (!isHidden(document)) scheduleBuild(0); }, 15000);
      scheduleBuild(1500);
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
    else start();
  });

  // ------------------------------------------------------------------ 13. Hiệu ứng menu
  /*
   * Menu của Zalo (thao tác hội thoại, Phân loại, "…", menu con) hiện / mất tức thì như trên máy tính. Ở đây:
   *   - Mở: thân menu (.zmenu-body) phóng từ góc gần nút bấm + mờ dần vào; menu con trượt vào từ phía menu cha.
   *   - Đóng: Zalo gỡ hẳn popover khỏi trang, nên vẽ một bản sao tại chỗ (không còn class popover-v3: các phần khác
   *     của shim không coi nó là menu đang mở) thu nhỏ + mờ đi rồi bỏ.
   *   - Menu mở từ thanh lọc (nút Phân loại, nút "…"): trải xuống từ mép trên rồi cuộn lại khi đóng; chạm lại đúng
   *     nút đó lúc menu đang mở thì đóng (như một nút bật / tắt).
   *   - Menu giữ lâu một dòng chat (zd-conv-pop): khi đóng, báo mục 12 hạ thẻ "focus" về thành dòng.
   *   - Chạm một mục: nền mục đậm lên ngay (trên cảm ứng :hover của Zalo đến muộn và dính lại).
   */
  safe(function () {
    if (!mobile || window.opener || window.top !== window) return;
    var EASE_IN = 'cubic-bezier(.2,.9,.25,1)', EASE_OUT = 'cubic-bezier(.4,0,1,1)';
    var CSS13 = [
      '@keyframes zd-pop-in { from { opacity: 0; transform: scale(.82); } to { opacity: 1; transform: none; } }',
      '@keyframes zd-pop-out { from { opacity: 1; } to { opacity: 0; } }',
      '@keyframes zd-sub-in { from { opacity: 0; transform: translateX(16px) scale(.96); } to { opacity: 1; transform: none; } }',
      '@keyframes zd-fade-in { from { opacity: 0; } to { opacity: 1; } }',
      'html.zd-m body > .popover-v3 > .zmenu-body { transform-origin: var(--zd-po, top right);' +
        ' animation: zd-pop-in .22s ' + EASE_IN + ' both; }',
      'html.zd-m .zd-pop-ghost { position: fixed !important; z-index: 20; margin: 0 !important; pointer-events: none !important; }',
      'html.zd-m .zd-pop-ghost > .zmenu-body { transform-origin: var(--zd-po, top right);' +
        ' animation: zd-pop-out .18s ease-out both !important; }',
      'html.zd-m .zmenu-sub.hovered > .zmenu-body.sub-menu { transform-origin: top right; animation: zd-sub-in .2s ' + EASE_IN + ' both; }',
      'html.zd-m .zmenu-body .zmenu-item { transition: background-color .12s; }',
      'html.zd-m .zmenu-body .zmenu-item:active { background-color: rgba(127,127,127,.18) !important; }',
      // Trải xuống từ mép trên (giãn theo chiều dọc), các mục trượt xuống theo; cuộn lại thì ngược lại. Chỉ dùng
      // transform + opacity (chạy trên GPU): clip-path phải vẽ lại từng khung hình, menu dài là giật.
      '@keyframes zd-drop-in { from { transform: scaleY(.35); opacity: 0; } to { transform: none; opacity: 1; } }',
      // Đóng: chỉ mờ dần tại chỗ (không thu lại).
      '@keyframes zd-drop-out { from { opacity: 1; } to { opacity: 0; } }',
      '@keyframes zd-drop-items { from { transform: translateY(-14px); opacity: 0; } to { transform: none; opacity: 1; } }',
      'html.zd-m body > .popover-v3.zd-drop > .zmenu-body { transform-origin: top center; animation: zd-drop-in .26s ' + EASE_IN + ' both; }',
      'html.zd-m body > .popover-v3.zd-drop > .zmenu-body .zl-scroll-menu { animation: zd-drop-items .3s ' + EASE_IN + ' both; }',
      'html.zd-m .zd-pop-ghost > .zmenu-body { will-change: transform, opacity; }',
      'html.zd-m .zd-pop-ghost.zd-drop > .zmenu-body { transform-origin: top center; animation: zd-drop-out .18s ease-out both !important; }'
    ].join('\n');
    function addCss() {
      try {
        var sheet = new CSSStyleSheet();
        sheet.replaceSync(CSS13);
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
      } catch (e) {
        var s = document.createElement('style');
        s.textContent = CSS13;
        (document.head || document.documentElement).appendChild(s);
      }
    }

    // Góc phóng menu: phía nút / dòng đã mở nó. Zalo đặt popover bằng right hoặc left; menu xổ xuống dưới nút.
    function origin(pop) {
      var st = pop.style, flip = pop.getAttribute('data-zd-flip') === '1';
      return (flip ? 'bottom ' : 'top ') + (st.right && st.right !== 'auto' ? 'right' : 'left');
    }
    function isMenu(n) { return n.nodeType === 1 && n.classList.contains('popover-v3') && !!n.querySelector('.zmenu-body'); }

    // Nút trên thanh lọc vừa được chạm (để đánh dấu menu nó mở ra là kiểu trải xuống) và menu nó đang mở.
    var barBtn = null, barAt = 0, dropOwner = null, dropPop = null;
    function barButton(t) {
      var bar = t && t.closest && t.closest('#sidebarNav .msg-filters-bar');
      if (!bar) return null;
      for (var e = t; e && e !== bar; e = e.parentElement) {
        if (/filter-preview|z--btn--v2/.test(e.className)) return e;
      }
      return null;
    }
    function closeMenus() {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true, cancelable: true }));
      ['mousedown', 'mouseup'].forEach(function (type) {
        document.body.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
      });
    }

    function ghost(pop, rect) {
      if (!rect || !rect.width) return;
      var g = pop.cloneNode(true);
      g.className = 'zd-pop-ghost' + (pop.classList.contains('zd-drop') ? ' zd-drop' : '');
      g.style.cssText = 'left:' + rect.left + 'px;top:' + rect.top + 'px;width:' + rect.width + 'px;--zd-po:' +
        pop.style.getPropertyValue('--zd-po');
      document.body.appendChild(g);
      setTimeout(function () { g.remove(); }, 240);
    }

    var rects = new WeakMap(); // vị trí cuối của từng menu đang mở (lúc bị gỡ thì không đo được nữa)
    function track() {
      [].forEach.call(document.querySelectorAll('body > .popover-v3'), function (p) {
        if (!isMenu(p)) return;
        rects.set(p, p.getBoundingClientRect());
        if (!p.style.getPropertyValue('--zd-po')) p.style.setProperty('--zd-po', origin(p));
      });
    }
    function added(n) {
      if (!isMenu(n) || !barBtn || Date.now() - barAt > 1500) return;
      n.classList.add('zd-drop');
      dropOwner = barBtn;
      dropPop = n;
      barBtn = null;
    }
    function removed(n) {
      if (!isMenu(n)) return;
      safe(function () { ghost(n, rects.get(n)); });
      if (n === dropPop) dropPop = dropOwner = null;
      if (n.classList.contains('zd-conv-pop') && api.convFocusEnd) api.convFocusEnd();
    }
    // Chạm lại nút đang mở menu: nuốt cả cử chỉ (Zalo sẽ đóng vì chạm ra ngoài rồi mở lại ngay) và tự đóng menu.
    var swallowUntil = 0;
    function swallow(e) {
      if (Date.now() > swallowUntil || !e.isTrusted) return; // sự kiện tự phát của closeMenus thì cho qua
      e.stopPropagation();
      e.preventDefault();
    }
    function start() {
      addCss();
      new MutationObserver(function (ms) {
        ms.forEach(function (m) {
          if (m.target !== document.body) return;
          [].forEach.call(m.addedNodes, function (n) { safe(function () { added(n); }); });
          [].forEach.call(m.removedNodes, removed);
        });
        safe(track);
      }).observe(document.body, { childList: true });
      // Chạm để đóng: đo vị trí menu ngay trước khi Zalo gỡ nó (lúc gắn vào Zalo / shim còn đang đặt lại vị trí).
      document.addEventListener('pointerdown', function (e) {
        safe(track);
        var b = barButton(e.target);
        if (b && dropPop && dropPop.isConnected && (b === dropOwner || b.contains(dropOwner) || dropOwner.contains(b))) {
          swallowUntil = Date.now() + 700;
          swallow(e);
          safe(closeMenus);
          return;
        }
        if (b) { barBtn = b; barAt = Date.now(); }
      }, true);
      ['mousedown', 'pointerup', 'mouseup', 'click', 'touchstart', 'touchend'].forEach(function (t) {
        window.addEventListener(t, swallow, true);
      });
      document.addEventListener('keydown', function () { safe(track); }, true);
    }
    if (document.body) start();
    else document.addEventListener('DOMContentLoaded', start);
    api.trackMenus = function () { safe(track); };
  });

  // ------------------------------------------------------------------ 14. Trình xem ảnh / video bung lên
  /*
   * Trình xem của Zalo (.zl-modal.image-show-modal) chỉ mờ dần vào / ra. Chạm ảnh / video trong khung chat: bản sao ảnh
   * (shim vẽ, dùng ảnh thu nhỏ trang đã tải sẵn) phóng từ đúng ô ảnh ra khung cuối của trình xem (vừa khít giữa màn hình
   * theo tỉ lệ ảnh), nền tối dần; trình xem của Zalo ẩn trong lúc đó (hiệu ứng mở của nó tắt hẳn — không thì bung hai
   * lần) rồi hiện ngay dưới bản sao. Đóng: bản sao thu về chỗ ô ảnh đang nằm, nền nhạt dần. Chỉ transform / opacity /
   * clip-path — chạy trên GPU.
   */
  safe(function () {
    if (!mobile || window.opener || window.top !== window) return;
    var EASE = 'cubic-bezier(.2,.9,.25,1)', from = null, fromAt = 0, last = null;
    var CSS14 = [
      '.zl-modal.zd-zoom { opacity: 0 !important; animation: none !important; }',
      // Hiệu ứng mở của Zalo: tắt hẳn trên khung trình xem (gỡ ra là CSS chạy lại từ đầu); transition bên trong chỉ tắt
      // trong lúc chuyển tiếp.
      '.zl-modal.zd-zoomed, .zl-modal.zd-zoomed .zl-modal__dialog, .zl-modal.zd-zoomed .media-viewer,' +
        ' .zl-modal.zd-zoomed .imageShowMainImage, .zl-modal.zd-zoomed .imageShowMainImage img { animation: none !important; }',
      '.zl-modal.zd-noanim, .zl-modal.zd-noanim * { animation: none !important; transition: none !important; }',
      '.zd-zoom-ghost { position: fixed; inset: 0; z-index: 2147483000; pointer-events: none; background-color: rgb(0,0,0);' +
        ' transition: background-color .3s ease-out; }',
      // Vạch "|" ngăn giữa các nút tải về / xoay / chia sẻ của trình xem: bỏ.
      '.media-viewer__footer action-group::before, .media-viewer__footer action-group::after { display: none !important; }'
    ].join('\n');
    function addCss() {
      try {
        var sheet = new CSSStyleSheet();
        sheet.replaceSync(CSS14);
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
      } catch (e) {
        var s = document.createElement('style');
        s.textContent = CSS14;
        (document.head || document.documentElement).appendChild(s);
      }
    }
    function fitRect(aspect) {
      var W = window.innerWidth, H = window.innerHeight, w = W, h = W / aspect;
      if (h > H) { h = H; w = H * aspect; }
      return { left: (W - w) / 2, top: (H - h) / 2, width: w, height: h };
    }
    /** Biến đổi để khung r2 trông như ô r1 (phủ kín, cắt phần thừa như ô center-crop, bo góc). */
    function zoomStyle(r1, r2, radius) {
      var s = Math.max(r1.width / r2.width, r1.height / r2.height);
      var tx = r1.left + r1.width / 2 - (r2.left + r2.width * s / 2), ty = r1.top + r1.height / 2 - (r2.top + r2.height * s / 2);
      var ix = Math.max(0, (r2.width - r1.width / s) / 2), iy = Math.max(0, (r2.height - r1.height / s) / 2);
      return { transform: 'translate(' + tx + 'px,' + ty + 'px) scale(' + s + ')',
        clip: 'inset(' + iy + 'px ' + ix + 'px round ' + (radius / s) + 'px)' };
    }
    function ghost(src, r) {
      var g = document.createElement('div');
      g.className = 'zd-zoom-ghost';
      var img = document.createElement('img');
      img.src = src;
      img.style.cssText = 'position:absolute;left:' + r.left + 'px;top:' + r.top + 'px;width:' + r.width + 'px;height:' +
        r.height + 'px;transform-origin:0 0;object-fit:cover;will-change:transform';
      g.appendChild(img);
      document.body.appendChild(g);
      return { g: g, img: img };
    }
    /** Ô ảnh trong bong bóng chat đang bị chạm (ảnh, ảnh trong album, khung hình video). */
    function mediaBox(t) {
      if (!t || !t.closest || !t.closest('#messageView')) return null;
      var box = t.closest('.img-center-box, [class*=video-message__non-caption], [class*=image-message]');
      var img = box && box.querySelector('img.zimg-el');
      return img && img.naturalWidth ? { box: box, img: img } : null;
    }
    function radiusOf(el) {
      for (var e = el, i = 0; e && i < 4; e = e.parentElement, i++) {
        var r = parseFloat(getComputedStyle(e).borderTopLeftRadius);
        if (r > 0) return r;
      }
      return 8;
    }
    function viewerMain(modal) {
      var l = modal.querySelector('.media-viewer__body__left'), best = null, area = 0;
      if (!l) return null;
      [].forEach.call(l.querySelectorAll('.imageShowMainImage img, img.zimg-el, video'), function (e) {
        var r = e.getBoundingClientRect();
        if (r.width * r.height > area && r.width > 40) { area = r.width * r.height; best = e; }
      });
      return best;
    }
    function open(modal) {
      var f = from;
      from = null;
      var to = fitRect(f.aspect), z = ghost(f.src, to), st = zoomStyle(f.rect, to, f.radius);
      modal.classList.add('zd-zoom', 'zd-zoomed', 'zd-noanim');
      z.g.style.backgroundColor = 'rgba(0,0,0,0)';
      z.img.style.transform = st.transform;
      z.img.style.clipPath = st.clip;
      z.g.getBoundingClientRect();
      z.g.style.backgroundColor = 'rgb(0,0,0)';
      z.img.style.transition = 'transform .3s ' + EASE + ', clip-path .3s ' + EASE;
      z.img.style.transform = 'none';
      z.img.style.clipPath = 'inset(0px 0px round 0px)';
      setTimeout(function () {
        modal.classList.remove('zd-zoom'); // trình xem hiện ngay dưới bản sao (bản sao vẫn che)
        // Bản sao giữ tới khi ảnh của Zalo hiện đúng chỗ (tối đa 0,5 s) rồi mới mờ đi — không thấy ảnh Zalo bật lên.
        var waited = 0;
        (function ready() {
          var el = viewerMain(modal), ok = el && getComputedStyle(el).visibility !== 'hidden' &&
            (el.tagName !== 'IMG' || el.complete && el.naturalWidth > 0);
          if (!ok && (waited += 40) < 500 && modal.isConnected) return void setTimeout(ready, 40);
          z.g.style.transition = 'opacity .15s linear';
          z.g.style.opacity = '0';
          setTimeout(function () { z.g.remove(); modal.classList.remove('zd-noanim'); }, 170);
        })();
      }, 310);
      last = { modal: modal, f: f };
    }
    function close() {
      var z = last;
      last = null;
      if (!z) return;
      // Ô ảnh còn trong khung chat thì thu về đúng chỗ nó đang nằm (khung có thể đã cuộn), không thì về chỗ cũ.
      var target = z.f.box.isConnected ? z.f.box.getBoundingClientRect() : z.f.rect;
      if (!target.width || target.bottom < 0 || target.top > window.innerHeight) target = z.f.rect;
      var r0 = fitRect(z.f.aspect), g = ghost(z.f.src, r0), st = zoomStyle(target, r0, z.f.radius);
      g.g.getBoundingClientRect();
      g.g.style.backgroundColor = 'rgba(0,0,0,0)';
      g.img.style.transition = 'transform .28s ' + EASE + ', clip-path .28s ' + EASE;
      g.img.style.transform = st.transform;
      g.img.style.clipPath = st.clip;
      setTimeout(function () { g.g.remove(); }, 300);
    }
    function start() {
      addCss();
      // Bắt cú chạm trước Zalo (pha capture): nhớ ô ảnh để lúc trình xem mở bung từ đó.
      // pointerdown: Zalo có thể mở trình xem ngay ở mousedown / pointerup, trước click.
      ['pointerdown', 'click'].forEach(function (type) {
        document.addEventListener(type, function (e) {
          var m = mediaBox(e.target);
          if (!m) return;
          from = { rect: m.box.getBoundingClientRect(), box: m.box, src: m.img.currentSrc || m.img.src,
            aspect: m.img.naturalWidth / m.img.naturalHeight, radius: radiusOf(m.box) };
          fromAt = Date.now();
        }, true);
      });
      new MutationObserver(function (ms) {
        ms.forEach(function (mu) {
          [].forEach.call(mu.addedNodes, function (n) {
            if (n.nodeType === 1 && n.classList.contains('zl-modal') && from && Date.now() - fromAt < 2500 &&
                (n.classList.contains('image-show-modal') || n.querySelector('.media-viewer'))) safe(function () { open(n); });
          });
          [].forEach.call(mu.removedNodes, function (n) {
            if (last && n.nodeType === 1 && (n === last.modal || n.contains(last.modal))) safe(close);
          });
        });
      }).observe(document.body, { childList: true, subtree: true });
    }
    if (document.body) start();
    else document.addEventListener('DOMContentLoaded', start);
  });

  // ------------------------------------------------------------------ 15. Tải trước ảnh / video của tin nhắn
  /*
   * Như Zalo mobile: khi tin nhắn của hội thoại được nạp, nhờ app tải ảnh HD + video của các tin (mới nhất trước) xuống
   * bộ nhớ của app (MediaCache) — bấm xem là có ngay, xem lại sau cũng không phải tải lại. Lấy link từ dữ liệu tin của
   * component khung chat của Zalo (props "messages"); mỗi link chỉ gửi một lần.
   */
  safe(function () {
    if (window.opener || window.top !== window) return;
    var sent = {}, timer = 0;
    function messages() {
      var sc = document.getElementById('messageViewScroll');
      var fk = sc && Object.keys(sc).filter(function (k) { return k.indexOf('__react') === 0; })[0];
      for (var f = fk && sc[fk], i = 0; f && i < 20; f = f.return, i++) {
        var p = f.memoizedProps;
        if (p && Array.isArray(p.messages) && typeof p.loadMoreMessages === 'function') return p.messages;
      }
      return null;
    }
    function collect() {
      var ms = messages();
      if (!ms) return;
      var urls = [];
      for (var i = ms.length - 1; i >= 0 && urls.length < 120; i--) {
        var c = ms[i].message;
        if (!c || typeof c !== 'object') continue;
        var pr = {};
        try { pr = typeof c.params === 'string' ? JSON.parse(c.params || '{}') : (c.params || {}); } catch (e) { /* ignore */ }
        [pr.hd, c.oriUrl, c.hdUrl, c.normalUrl, c.thumbUrl, c.href && /\.(mp4|jpe?g|png|webp)/i.test(c.href) ? c.href : '']
          .forEach(function (u) {
            if (typeof u === 'string' && /^https:/.test(u) && !sent[u]) { sent[u] = 1; urls.push(u); }
          });
      }
      if (urls.length) bridge.prefetch(JSON.stringify(urls));
    }
    function start() {
      // Trang đổi (mở hội thoại, tin mới, nạp tin cũ): gom lại, tối đa mỗi 0,8 s một lần (trang đổi liên tục, đặt
      // lại hẹn giờ mỗi lần thì có thể không bao giờ chạy).
      new MutationObserver(function () {
        if (timer) return;
        timer = setTimeout(function () { timer = 0; safe(collect); }, 800);
      }).observe(document.body, { childList: true, subtree: true });
    }
    if (document.body) start();
    else document.addEventListener('DOMContentLoaded', start);
  });

  // ------------------------------------------------------------------ 16. Video mở ra là có tiếng
  /*
   * Bộ phát video là của WebView từng hãng (Huawei, Chrome...) hoặc của Zalo, có khi mở ra đang tắt tiếng. Mỗi thẻ video
   * mới trong trình xem: bỏ tắt tiếng, âm lượng 0 thì về 100% — một lần cho mỗi video, người dùng tự tắt tiếng thì thôi.
   */
  safe(function () {
    if (!mobile || window.opener || window.top !== window) return;
    var seen = typeof WeakSet === 'function' ? new WeakSet() : null, queued = false;
    function sync() {
      var v = document.querySelector('.media-viewer .mediaViewerMain video');
      if (!v || !seen || seen.has(v)) return;
      seen.add(v);
      v.muted = false;
      if (v.volume === 0) v.volume = 1;
      // Zalo có thể đặt lại muted khi nạp nguồn: áp lại một lần lúc video sẵn sàng.
      v.addEventListener('loadedmetadata', function () { v.muted = false; if (v.volume === 0) v.volume = 1; }, { once: true });
    }
    function start() {
      new MutationObserver(function () {
        if (queued) return;
        queued = true;
        requestAnimationFrame(function () { queued = false; safe(sync); });
      }).observe(document.body, { childList: true, subtree: true });
    }
    if (document.body) start();
    else document.addEventListener('DOMContentLoaded', start);
  });

  // ------------------------------------------------------------------ 17. Xem tin nhắn đã thu hồi
  /*
   * Tin bị thu hồi vẫn nằm trong dữ liệu của trang (msgType 20, originMsgType "chat.undo"): "message" thường còn nội dung
   * gốc; khi Zalo đã thay bằng "[Tin nhắn đã được thu hồi]" thì chữ gốc vẫn còn trong z_parsedTokens (chỉ mục tìm kiếm).
   * Thêm phòng hờ: chữ của mọi tin đã thấy trong khung chat được nhớ lại (localStorage, theo cliMsgId). Dưới dòng
   * "Tin nhắn đã được thu hồi" (#bb_msg_id_<cliMsgId> .undo-message) hiện nội dung gốc. Chỉ có khi trang đã nhận được tin
   * trước lúc bị thu hồi.
   */
  safe(function () {
    if (window.opener || window.top !== window) return;
    var KEY = 'zd_seen_msgs', MAX = 3000, seen = null, dirty = false, timer = 0;
    var CSS17 = [
      '.zd-undo-orig { margin-top: 4px; padding: 4px 8px; border-left: 2px solid currentColor; border-radius: 2px;',
      '  font-style: normal; white-space: pre-wrap; word-break: break-word; opacity: .85; }',
      '.zd-undo-orig b { display: block; font-size: 11px; font-weight: 600; opacity: .7; }',
      '.zd-undo-orig img { display: block; max-width: 200px; max-height: 200px; margin-top: 4px; border-radius: 6px; }'
    ].join('\n');
    function load() {
      if (seen) return seen;
      try { seen = JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch (e) { seen = {}; }
      return seen;
    }
    function save() {
      if (!dirty) return;
      dirty = false;
      var ks = Object.keys(seen);
      if (ks.length > MAX) { // bỏ các tin cũ nhất (cliMsgId tăng theo thời gian)
        ks.sort(function (a, b) { return a.length - b.length || (a < b ? -1 : 1); });
        ks.slice(0, ks.length - MAX).forEach(function (k) { delete seen[k]; });
      }
      try { localStorage.setItem(KEY, JSON.stringify(seen)); } catch (e) { /* đầy: bỏ qua */ }
    }
    function messages() {
      var sc = document.getElementById('messageViewScroll');
      var fk = sc && Object.keys(sc).filter(function (k) { return k.indexOf('__react') === 0; })[0];
      for (var f = fk && sc[fk], i = 0; f && i < 20; f = f.return, i++) {
        var p = f.memoizedProps;
        if (p && Array.isArray(p.messages) && typeof p.loadMoreMessages === 'function') return p.messages;
      }
      return null;
    }
    function isUndo(m) {
      return m.msgType === 20 || m.originMsgType === 'chat.undo';
    }
    function placeholder(s) {
      return /^\[.*thu hồi.*\]$/i.test(s) || /^\[.*recall.*\]$/i.test(s);
    }
    function jpg(u) {
      return u.replace(/\/(\w+)\/jxl\//, '/$1/jpg/').replace(/\.jxl(\?|$)/, '.jpg$1');
    }
    /** Nội dung gốc của một tin: { text, img } hoặc null. */
    function original(m) {
      var c = m.message;
      if (typeof c === 'string') {
        if (c && !placeholder(c)) return { text: c };
      } else if (c && typeof c === 'object') {
        var pr = {};
        try { pr = typeof c.params === 'string' ? JSON.parse(c.params || '{}') : (c.params || {}); } catch (e) { /* ignore */ }
        var img = [c.thumbUrl, c.thumb, c.normalUrl, c.hdUrl, pr.hd].filter(function (u) {
          return typeof u === 'string' && /^https:/.test(u);
        })[0];
        var t = c.title || c.description || (typeof c.href === 'string' && !img ? c.href : '');
        if (img || t) return { text: t || '', img: img ? jpg(img) : '' };
      }
      var tk = m.z_parsedTokens;
      try { if (typeof tk === 'string') tk = JSON.parse(tk); } catch (e) { tk = null; }
      if (Array.isArray(tk) && tk.length && tk.join('').trim()) return { text: tk.join(' ') };
      var s = load()[String(m.cliMsgId)];
      return s ? { text: s } : null;
    }
    function run() {
      var ms = messages();
      if (!ms) return;
      var by = {};
      load();
      for (var i = 0; i < ms.length; i++) {
        var m = ms[i];
        if (!m || m.cliMsgId == null) continue;
        by[String(m.cliMsgId)] = m;
        if (!isUndo(m) && typeof m.message === 'string' && m.message && !seen[m.cliMsgId]) {
          seen[m.cliMsgId] = m.message.slice(0, 2000);
          dirty = true;
        }
      }
      save();
      var spans = document.querySelectorAll('#messageViewScroll .undo-message');
      for (var j = 0; j < spans.length; j++) {
        var sp = spans[j], box = sp.parentElement;
        if (!box || box.querySelector('.zd-undo-orig')) continue;
        var row = sp.closest('[id^="bb_msg_id_"]');
        var m2 = row && by[row.id.slice('bb_msg_id_'.length)];
        var o = m2 && original(m2);
        if (!o) continue;
        var d = document.createElement('div');
        d.className = 'zd-undo-orig';
        var b = document.createElement('b');
        b.textContent = 'Nội dung đã thu hồi';
        d.appendChild(b);
        if (o.text) d.appendChild(document.createTextNode(o.text));
        if (o.img) {
          var im = document.createElement('img');
          im.loading = 'lazy';
          im.src = o.img;
          im.onerror = function () { this.remove(); };
          d.appendChild(im);
        }
        box.appendChild(d);
      }
    }
    function start() {
      try {
        var sheet = new CSSStyleSheet();
        sheet.replaceSync(CSS17);
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
      } catch (e) {
        var s = document.createElement('style');
        s.textContent = CSS17;
        (document.head || document.documentElement).appendChild(s);
      }
      new MutationObserver(function () {
        if (timer) return;
        timer = setTimeout(function () { timer = 0; safe(run); }, 300);
      }).observe(document.body, { childList: true, subtree: true });
    }
    if (document.body) start();
    else document.addEventListener('DOMContentLoaded', start);
  });

  // ------------------------------------------------------------------ 18. Bảng biểu cảm thay chỗ bàn phím
  /*
   * Nút Biểu cảm / Sticker mở bảng .media-dock trong một popover (body > .popover-v3). Zalo đóng popover khi cửa sổ đổi
   * kích thước, nên bấm lúc bàn phím đang bật thì bàn phím đóng (ô nhập mất focus), cửa sổ cao lên và bảng vừa mở bị đóng
   * luôn — phải bấm lần nữa. Ở đây: bấm khi bàn phím bật thì giữ lại cú bấm, cất bàn phím, đợi cửa sổ cao lên rồi mới bấm
   * hộ nút. Bảng thành tấm dưới đáy, cao bằng bàn phím (đo lần gần nhất), khung chat chừa chỗ phía trên như app Zalo.
   * Bảng đang mở mà bấm lại nút thì Zalo đóng bảng, ô nhập được focus lại (bàn phím lên).
   */
  safe(function () {
    if (!mobile || window.opener || window.top !== window) return;
    var KB = 'zd_kb_h', full = innerHeight, kb = 0, held = 0;
    try { kb = parseInt(localStorage.getItem(KB), 10) || 0; } catch (e) { /* ignore */ }
    function setKb(h) {
      kb = Math.max(220, Math.min(Math.round(h), Math.round(full * 0.6) || 420)); // full: cao khi không có bàn phím
      document.documentElement.style.setProperty('--zd-kb', kb + 'px');
    }
    setKb(kb || 280);
    var CSS18 = [
      'html.zd-m body > .popover-v3:has(> .media-dock) { left: 0 !important; right: 0 !important; top: auto !important;',
      '  bottom: 0 !important; width: 100% !important; transform: none !important; box-sizing: border-box !important;',
      '  padding: 0 !important; border-width: 1px 0 0 !important; border-radius: 0 !important; }',
      'html.zd-m body > .popover-v3 > .media-dock { width: 100% !important; height: var(--zd-kb) !important;',
      '  margin: 0 !important; border-radius: 0 !important; }',
      'html.zd-m:has(body > .popover-v3 > .media-dock) [data-zd=main] { padding-bottom: var(--zd-kb) !important;',
      '  box-sizing: border-box !important; }'
    ].join('\n');
    function input() {
      var a = document.activeElement;
      return a && a.isContentEditable && M.main && M.main.contains(a) ? a : null;
    }
    function panelOpen() {
      return !!document.querySelector('body > .popover-v3 > .media-dock');
    }
    function btnOf(t) {
      return t instanceof Element ? t.closest('[icon="Emoji_24_Line"], [icon="Sticker_24_Line"]') : null;
    }
    // Đo bàn phím: ô nhập đang focus mà cửa sổ thấp đi so với lúc không có bàn phím.
    addEventListener('resize', function () {
      if (!input()) {
        full = innerHeight;
        return;
      }
      var h = full - innerHeight;
      if (h > 150) {
        setKb(h);
        try { localStorage.setItem(KB, String(kb)); } catch (e) { /* ignore */ }
      }
    });
    document.addEventListener('pointerdown', function (e) {
      var b = btnOf(e.target);
      if (!b) return;
      var inp = input();
      if (panelOpen()) {
        // Bấm lại nút khi bảng đang mở: Zalo đóng bảng; đưa bàn phím lên lại.
        var box = document.getElementById('richInput');
        if (box) setTimeout(function () { safe(function () { box.focus(); bridge.showKeyboard(); }); }, 80);
        return;
      }
      if (!inp) return;
      e.preventDefault(); // giữ cú bấm, tự cất bàn phím
      held = Date.now();
      var h0 = innerHeight, done = false;
      function open() {
        if (done) return;
        done = true;
        removeEventListener('resize', onResize);
        setTimeout(function () { safe(function () { b.click(); }); }, 50);
      }
      function onResize() { if (innerHeight > h0 + 80) open(); }
      addEventListener('resize', onResize);
      setTimeout(open, 700);
      inp.blur();
    }, true);
    // Danh sách @nhắc tên (#mentionPopover, trong body): chạm chọn tên mà ô nhập mất focus thì bàn phím đóng. Giữ focus ở
    // ô nhập (chặn mặc định của pointerdown — không ảnh hưởng cuộn). Lúc chèn tên Zalo vẫn tự bỏ focus rồi focus lại ô
    // nhập, bàn phím đã đóng thì không tự lên: nhờ app mở lại.
    var mentionAt = 0;
    document.addEventListener('pointerdown', function (e) {
      if (!(e.target instanceof Element) || !e.target.closest('#mentionPopover, .mention-popover') || !input()) return;
      e.preventDefault();
      mentionAt = Date.now();
    }, true);
    document.addEventListener('click', function (e) {
      if (!mentionAt || Date.now() - mentionAt > 1500 || !(e.target instanceof Element) ||
        !e.target.closest('.mention-popover__item')) return;
      mentionAt = 0;
      setTimeout(function () { if (input()) bridge.showKeyboard(); }, 120);
    }, true);
    // Cú bấm thật đi sau pointerdown đã giữ lại: chặn, nút được bấm hộ khi bàn phím đã cất.
    document.addEventListener('click', function (e) {
      if (!e.isTrusted || !held || Date.now() - held > 1000 || !btnOf(e.target)) return;
      held = 0;
      e.preventDefault();
      e.stopImmediatePropagation();
    }, true);
    try {
      var sheet = new CSSStyleSheet();
      sheet.replaceSync(CSS18);
      document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
    } catch (e) {
      var s = document.createElement('style');
      s.textContent = CSS18;
      (document.head || document.documentElement).appendChild(s);
    }
  });

  // ------------------------------------------------------------------ 19. Sự kiện cuộc gọi từ WebSocket
  /*
   * Zalo Web nhận lời mời gọi đến qua chính WebSocket tin nhắn (zpw_ws) nhưng không nghe được. Đọc các khung điều khiển
   * (601–604) để app tự xử lý cuộc gọi. Khung: [u8 version, u16 LE cmd, u8 subCmd] + JSON {encrypt, data}. Khung đầu
   * (cmd 1, AUTHEN) mang khoá AES-GCM (base64, trường key); data mã hoá theo encrypt: 0 thô, 1 base64+zlib,
   * 2 AES-GCM+zlib, 3 AES-GCM; khối AES-GCM = iv(16) | aad(16) | bản mã+tag. Chỉ đọc, không gửi gì lên máy chủ;
   * khung tin nhắn (5xx) không giải mã.
   */
  safe(function () {
    if (window.opener || window.top !== window || !window.crypto || !crypto.subtle) return;
    var keys = {}, chains = {};
    var td = new TextDecoder();
    function isCtrl(cmd) { return cmd >= 601 && cmd <= 604; }
    function b64bytes(s) {
      var bin = atob(s), out = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
      return out;
    }
    function inflate(bytes) {
      if (!window.DecompressionStream) return Promise.reject(new Error('no DecompressionStream'));
      var kinds = bytes[0] === 0x1f && bytes[1] === 0x8b ? ['gzip', 'deflate'] : ['deflate', 'gzip'];
      kinds.push('deflate-raw');
      function attempt(i) {
        if (i >= kinds.length) return Promise.reject(new Error('inflate failed'));
        var st;
        try { st = new Blob([bytes]).stream().pipeThrough(new DecompressionStream(kinds[i])); } catch (e) { return attempt(i + 1); }
        return new Response(st).arrayBuffer().then(function (b) { return new Uint8Array(b); }, function () { return attempt(i + 1); });
      }
      return attempt(0);
    }
    function gcm(key, blob) {
      if (!key) return Promise.reject(new Error('no cipher key'));
      if (blob.length < 48) return Promise.reject(new Error('blob too short ' + blob.length));
      return crypto.subtle.decrypt({ name: 'AES-GCM', iv: blob.subarray(0, 16), additionalData: blob.subarray(16, 32), tagLength: 128 },
        key, blob.subarray(32)).then(function (b) { return new Uint8Array(b); });
    }
    function decryptData(key, enc, data) {
      if (enc === 0) return Promise.resolve(new TextEncoder().encode(data));
      if (enc === 1) return inflate(b64bytes(data));
      var raw;
      try { raw = b64bytes(decodeURIComponent(data)); } catch (e) { raw = b64bytes(data); }
      if (enc === 2) return gcm(key, raw).then(inflate);
      if (enc === 3) return gcm(key, raw);
      return Promise.reject(new Error('encrypt ' + enc));
    }
    function toBytes(d) {
      if (d instanceof ArrayBuffer) return Promise.resolve(new Uint8Array(d));
      if (ArrayBuffer.isView(d)) return Promise.resolve(new Uint8Array(d.buffer, d.byteOffset, d.byteLength));
      if (window.Blob && d instanceof Blob) return d.arrayBuffer().then(function (b) { return new Uint8Array(b); });
      return Promise.resolve(null); // khung chữ: Zalo không dùng
    }
    function report(ws, dir, cmd, sub, body) {
      safe(function () { bridge.callEvent(dir, ws, cmd, sub, typeof body === 'string' ? body : JSON.stringify(body)); });
    }
    function handle(ws, bytes) {
      if (!bytes || bytes.length < 4) return null;
      var cmd = bytes[1] | (bytes[2] << 8), sub = bytes[3];
      if (cmd !== 1 && !isCtrl(cmd)) return null;
      var env;
      try { env = JSON.parse(td.decode(bytes.subarray(4))); } catch (e) { report(ws, 'rx', cmd, sub, '{"_err":"bad json"}'); return null; }
      if (!env || typeof env !== 'object') return null;
      var enc = +env.encrypt || 0, p;
      if (cmd === 1 && env.key) {
        p = crypto.subtle.importKey('raw', b64bytes(env.key), 'AES-GCM', false, ['decrypt']).then(function (k) { keys[ws] = k; });
      } else p = Promise.resolve();
      return p.then(function () {
        if (typeof env.data !== 'string') return env;
        return decryptData(keys[ws], enc, env.data).then(function (b) {
          var txt = td.decode(b);
          try { return JSON.parse(txt); } catch (e) { return { _raw: txt.slice(0, 4000) }; }
        });
      }).then(function (body) {
        if (cmd === 1) {
          // Chỉ ghi danh sách hàng đợi máy chủ cho kéo (có 604 VoIP hay không), không ghi phần còn lại của phiên.
          var d = (body && body.data) || body || {};
          report(ws, 'rx', 1, sub, { error_code: body && body.error_code, ctrl_qCmds: d.ctrl_qCmds, qCmds: d.qCmds, encrypt: enc });
        } else report(ws, 'rx', cmd, sub, body);
      }, function (e) { report(ws, 'rx', cmd, sub, { _err: String(e && e.message || e), encrypt: enc }); });
    }
    api.callTap = function (ws, data) {
      // Giữ thứ tự theo từng socket: khoá AUTHEN phải có trước khi giải mã các khung sau.
      chains[ws] = (chains[ws] || Promise.resolve()).then(function () { return toBytes(data); })
        .then(function (b) { return handle(ws, b); }).catch(function (e) { log('callTap: ' + e); });
    };
    api.callTapSend = function (ws, data) {
      // Lệnh kéo hàng đợi điều khiển của trang là JSON thô: ghi lại để biết trang có kéo 604 không.
      toBytes(data).then(function (b) {
        if (!b || b.length < 4) return;
        var cmd = b[1] | (b[2] << 8), sub = b[3];
        if (!isCtrl(cmd)) return;
        report(ws, 'tx', cmd, sub, td.decode(b.subarray(4)).slice(0, 1000));
      }).catch(function () { /* ignore */ });
    };

    /*
     * Báo hiệu cuộc gọi (app gọi qua evaluateJavascript): dùng chính các hàm của Zalo Web (module chứa requestCall,
     * sendRequestCall...), nên khoá phiên, imei và tham số chung do trang tự lo, app không cần giữ khoá. Kết quả trả về
     * qua bridge.callSigResult(reqId, ok, json). Module webpack tìm theo tên hàm, không theo mã module (đổi mỗi bản).
     */
    var sigApi = null, aesApi = null;
    function wreq() {
      if (window.__zdWr) return window.__zdWr;
      if (!window.webpackJsonp) return null;
      var id = 'zdcall' + Date.now();
      var mods = {};
      mods[id] = function (m, e, r) { window.__zdWr = r; };
      window.webpackJsonp.push([[id], mods, [[id]]]);
      return window.__zdWr || null;
    }
    function findApis() {
      if (sigApi) return true;
      var r = wreq();
      if (!r || !r.c) return false;
      var ids = Object.keys(r.c);
      for (var i = 0; i < ids.length && !(sigApi && aesApi); i++) {
        var ex; try { ex = r.c[ids[i]].exports; } catch (e) { continue; }
        if (!ex) continue;
        var d = ex['default'] || ex;
        if (!d || (typeof d !== 'function' && typeof d !== 'object')) continue;
        try {
          if (!sigApi && typeof d.requestCall === 'function' && typeof d.sendRequestCall === 'function') sigApi = d;
          if (!aesApi && typeof d.encodeAES === 'function' && typeof d.decodeAES === 'function' && typeof d.getSecretKey === 'function') aesApi = d;
        } catch (e) { /* ignore */ }
      }
      return !!sigApi;
    }
    function unwrap(res) {
      var body = res && res.data !== undefined ? res.data : res;
      if (body && typeof body === 'object' && typeof body.data === 'string' && aesApi) {
        var s = body.data, parsed = null;
        try { parsed = JSON.parse(s); } catch (e) {
          try { parsed = JSON.parse(aesApi.decodeAES(s)); } catch (e2) { /* để nguyên */ }
        }
        if (parsed !== null) body = Object.assign({}, body, { data: parsed });
      }
      if (body && typeof body === 'object') { try { delete body.__zdelayData; } catch (e) { /* ignore */ } }
      return body;
    }
    var SIG_OPS = { requestCall: 1, sendRequestCall: 1, sendRingRingCall: 1, sendAnswerCall: 1, sendAnswerACKCall: 1,
      sendCancelCall: 1, sendEndCall: 1, sendLogEndCall: 1, sendOnOffCamMic: 1,
      requestCallGroup: 1, sendRequestCallGroup: 1, sendCancelCallGroup: 1, sendEndCallGroup: 1, sendCallGroupFinish: 1 };
    // sendRingRingCall (407) và sendAnswerCall (402) đã có ở trên: dùng cho cuộc gọi đến.
    api.callSig = function (reqId, op, argsJson) {
      function done(ok, v) {
        var s; try { s = JSON.stringify(v); } catch (e) { s = '{"_err":"stringify"}'; }
        safe(function () { bridge.callSigResult(String(reqId), !!ok, s); });
      }
      try {
        if (op === 'upgradeSession') return upgradeSession(done);
        if (!SIG_OPS[op]) return done(false, { _err: 'op ' + op });
        if (!findApis()) return done(false, { _err: 'zalo call api not found' });
        var args = JSON.parse(argsJson || '[]');
        Promise.resolve(sigApi[op].apply(sigApi, args)).then(function (res) { done(true, unwrap(res)); },
          function (e) { done(false, { _err: String(e && (e.message || e.code) || e), status: e && e.status }); });
      } catch (e) { done(false, { _err: String(e && e.message || e) }); }
    };
    /*
     * Nhận cuộc gọi đến: máy chủ chỉ đẩy lời mời (act "request") cho phiên kiểu Zalo PC. Gọi lại getLoginInfo của chính
     * trang với type 24 / client_version 665 (như ctdozlite upgrade_session_for_group_call) rồi trả cấu hình về 30 ngay;
     * các yêu cầu khác của trang vẫn là Zalo Web. Kết quả: {error_code, haspcclient}. Không trả khoá phiên ra ngoài.
     */
    var cfgApi = null;
    function upgradeSession(done) {
      if (!findApis() || typeof sigApi.getLoginInfo !== 'function') return done(false, { _err: 'getLoginInfo not found' });
      if (!cfgApi) {
        var r = wreq(), ids = r && r.c ? Object.keys(r.c) : [];
        for (var i = 0; i < ids.length && !cfgApi; i++) {
          try {
            var d = r.c[ids[i]].exports && r.c[ids[i]].exports['default'];
            if (d && typeof d === 'object' && 'apiType' in d && 'apiVersion' in d && 'apiVoiceCallDomain' in d) cfgApi = d;
          } catch (e) { /* ignore */ }
        }
      }
      if (!cfgApi) return done(false, { _err: 'config not found' });
      var oldT = cfgApi.apiType, oldV = cfgApi.apiVersion;
      cfgApi.apiType = 24;
      cfgApi.apiVersion = 665;
      var p;
      try { p = sigApi.getLoginInfo(localStorage.getItem('z_uuid'), 'vi'); } finally {
        cfgApi.apiType = oldT;
        cfgApi.apiVersion = oldV;
      }
      Promise.resolve(p).then(function (res) {
        var d = res && res.data, inner = d && d.data;
        done(true, { error_code: d ? d.error_code : -1, error_message: d && d.error_message,
          haspcclient: inner && typeof inner === 'object' ? inner.haspcclient : undefined });
      }, function (e) { done(false, { _err: String(e && (e.message || e.code) || e) }); });
    }

    /** Gọi đi bằng app (thay cho Zalo Web không gọi được). uid: mã người dùng Zalo (id hội thoại 1-1). */
    api.startCall = function (uid, name, avatar, video) {
      bridge.startCall(String(uid), String(name || ''), String(avatar || ''), !!video);
    };

    /* Hội thoại đang mở: props "conversation" (React) của khung tin nhắn — userId, tên, ảnh, nhóm hay không. */
    function openConversation() {
      var sc = document.getElementById('messageViewScroll');
      var fk = sc && Object.keys(sc).filter(function (k) { return k.indexOf('__react') === 0; })[0];
      for (var f = fk && sc[fk], i = 0; f && i < 40; f = f.return, i++) {
        var p = f.memoizedProps;
        if (p && p.conversation && p.conversation.userId) return p.conversation;
      }
      return null;
    }
    function isGroupConv(c) { return !!(c && (c.isGroup || /^g\d/.test(String(c.userId)))); }
    function callable(c) {
      // 1-1 với người dùng thật hoặc nhóm: không phải Official Account, "Cloud của tôi" (send2me).
      if (!c || c.oaInfo || /send2me|sendtome/i.test(String(c.userId))) return false;
      return isGroupConv(c) || String(c.userId).length > 6;
    }
    function meUid() {
      var r = wreq(), ids = r && r.c ? Object.keys(r.c) : [];
      for (var i = 0; i < ids.length; i++) {
        try {
          var d = r.c[ids[i]].exports && r.c[ids[i]].exports['default'];
          if (d && typeof d.getUidMe === 'function') return String(d.getUidMe() || '');
        } catch (e) { /* ignore */ }
      }
      return '';
    }
    function callOpen(from, video) {
      var c = openConversation();
      if (!callable(c)) { log('call: conversation not callable (' + from + ')'); return false; }
      if (isGroupConv(c)) {
        // Gọi nhóm (chỉ thoại): mã nhóm bỏ tiền tố "g"; danh sách thành viên trừ mình.
        var me = meUid(), mem = [];
        (c.memberIds || []).forEach(function (m) {
          var id = String(m).split('_')[0];
          if (id && id !== me && mem.indexOf(id) < 0) mem.push(id);
        });
        if (!mem.length && c.topMember) c.topMember.forEach(function (m) {
          var id = String(m && (m.id || m.userId) || '');
          if (id && id !== me && mem.indexOf(id) < 0) mem.push(id);
        });
        var total = +c.totalMember || mem.length + 1;
        bridge.startGroupCall(String(c.userId).replace(/^g/, ''), c.displayName || '', c.avatar || '', JSON.stringify(mem), String(total));
        return true;
      }
      api.startCall(c.userId, c.displayName || c.zaloName || '', c.avatar || '', !!video);
      return true;
    }

    // Bỏ nút "thêm thành viên / tạo nhóm" trên thanh trên của mọi khung chat cho đỡ chật (vẫn thêm được trong
    // Thông tin hội thoại). Hàng nút đó là hàng có ô tìm tin nhắn.
    safe(function () {
      var st = document.createElement('style');
      st.textContent = ':has(> .rel .search-message-entry__icon) > .rel:has(i[class*="_Create_24_Line"]) { display: none !important; }';
      (document.head || document.documentElement).appendChild(st);
    });
    // Nút gọi thoại / gọi video trên thanh trên của khung chat (Zalo Web không có), đặt trước ô tìm tin nhắn.
    // Icon video: fa-icon-outline-video (fa-outline-video của Zalo có chữ "BETA" bên dưới nên lệch hàng).
    function headerButton(cls, icon, title, video) {
      var b = document.createElement('div');
      b.className = 'z--btn--v2 btn-tertiary-neutral medium --rounded icon-only zd-call-btn ' + cls;
      // Các nút sẵn có của Zalo nằm trong khung .rel.margin-right-4: cách nhau 4px. Giữ đúng khoảng đó cho thẳng hàng.
      b.style.marginRight = '4px';
      b.setAttribute('title', title);
      b.setAttribute('role', 'button');
      b.innerHTML = '<i class="fa ' + icon + ' pre"></i>';
      b.addEventListener('click', function (e) { e.preventDefault(); e.stopPropagation(); callOpen('header', video); }, true);
      return b;
    }
    setInterval(function () {
      safe(function () {
        var inChat = document.documentElement.classList.contains('zd-chat') || !!document.getElementById('messageViewScroll');
        var old = document.querySelectorAll('.zd-call-btn');
        if (!inChat) { old.forEach(function (x) { x.remove(); }); return; }
        var conv = openConversation(), ok = callable(conv), grp = isGroupConv(conv);
        if (old.length) {
          old.forEach(function (x) {
            // Nhóm: như Zalo trên điện thoại chỉ có nút gọi video (bắt đầu cuộc gọi nhóm); 1-1 có cả hai.
            x.style.display = ok && !(grp && x.classList.contains('zd-call-voice')) ? '' : 'none';
          });
          return;
        }
        if (!ok) return;
        var icon = document.querySelector('.search-message-entry__icon');
        var anchor = icon && icon.closest('.rel');
        if (!anchor || !anchor.parentElement) return;
        anchor.parentElement.insertBefore(headerButton('zd-call-voice', 'fa-outline-call', 'Gọi thoại', false), anchor);
        anchor.parentElement.insertBefore(headerButton('zd-call-video', 'fa-icon-outline-video', 'Gọi video', true), anchor);
      });
    }, 700);

    // "Gọi lại" trong tin nhắn cuộc gọi: Zalo Web chỉ báo "hãy dùng app" — gọi bằng ZaloDuo (video nếu là cuộc gọi video).
    document.addEventListener('click', function (e) {
      var t = e.target && e.target.closest && e.target.closest('.call-message__callback-wrapper');
      if (!t) return;
      var msg = t.closest('.call-message');
      var video = !!(msg && /video/i.test(msg.textContent || ''));
      if (callOpen('callback', video)) { e.preventDefault(); e.stopImmediatePropagation(); }
    }, true);
  });

  api.version = 11;
})();
