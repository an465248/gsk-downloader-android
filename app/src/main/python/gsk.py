"""On-device extractor: yt-dlp phone par chalta hai.

Isliye video-info nikalna + saare network calls USER ke
IP / network / CPU / RAM se hote hain. Koi server involved nahi.
"""
import concurrent.futures
import json
import os
import re
import threading
import time
import yt_dlp

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")

# Facebook apna video-page mobile UA par aadha deta hai ("Cannot parse data").
# Verified: desktop UA par sd+hd dono progressive mp4 milte hain — BINA LOGIN.
UA_DESKTOP = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")


def _ua_for(url):
    u = (url or "").lower()
    if "facebook.com" in u or "fb.watch" in u:
        return UA_DESKTOP
    return UA


# ---------------- extract cache (repeat fetch = instant) ----------------
_EXTRACT_CACHE = {}
_EXTRACT_CACHE_TTL = 180  # sec


def _cache_key(url, cookiefile=""):
    try:
        stamp = ""
        if cookiefile and os.path.isfile(cookiefile):
            st = os.stat(cookiefile)
            stamp = "%d:%d" % (st.st_size, int(st.st_mtime))
        return (url, stamp)
    except Exception:
        return (url, "")


def _cache_get(key):
    try:
        ts, resp = _EXTRACT_CACHE.get(key) or (0, None)
        if resp is not None and (time.time() - ts) < _EXTRACT_CACHE_TTL:
            return json.loads(resp)
        if resp is not None:
            _EXTRACT_CACHE.pop(key, None)
    except Exception:
        pass
    return None


def _cache_put(key, resp):
    try:
        if len(_EXTRACT_CACHE) >= 64:
            _EXTRACT_CACHE.pop(next(iter(_EXTRACT_CACHE)), None)
        _EXTRACT_CACHE[key] = (time.time(), json.dumps(resp, ensure_ascii=False))
    except Exception:
        pass


def _parse_youtube_id(url):
    try:
        u = url or ""
        m = re.search(r"[?&]v=([A-Za-z0-9_-]{6,32})", u)
        if m:
            return m.group(1)
        m = re.search(r"youtu\.be/([A-Za-z0-9_-]{6,32})", u)
        if m:
            return m.group(1)
        m = re.search(r"youtube\.com/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{6,32})", u)
        if m:
            return m.group(1)
    except Exception:
        pass
    return ""


def _moov_complete(d):
    """moov box ka size padhkar batao poora buffer me hai ya kata hua."""
    import struct
    try:
        j = d.find(b"moov")
        while 0 <= j:
            if j >= 4:
                size = struct.unpack(">I", d[j - 4:j])[0]
                if 8 <= size <= 100 * 1024 * 1024:
                    return (j - 4 + size) <= len(d)
            j = d.find(b"moov", j + 4)
            if j < 0:
                return False
        return False
    except Exception:
        return False


def _probe_mp4_tracks(url, timeout=4):
    """MP4 ke pehle 300KB (Range) se (width, height, has_audio). Fail-soft.

    has_audio: True = audio track pakka, False = moov poora mila aur audio
    track NAHI (video-only file), None = pata nahi chala (purana
    assumption barkarar). (FIX: IG music-reels video-ONLY hoti hain —
    bina verify direct download BINA AAWAZ deta tha.)
    """
    import urllib.request
    import struct
    try:
        req = urllib.request.Request(
            url, headers={"User-Agent": UA_DESKTOP,
                          "Range": "bytes=0-307199", "Accept": "*/*"})
        with urllib.request.urlopen(req, timeout=timeout) as res:
            d = res.read(320000)
        if len(d) < 128 or b"moov" not in d:
            return None
        pos, best = 0, None
        while True:
            j = d.find(b"tkhd", pos)
            if j < 0:
                break
            try:
                ver = d[j + 4]
                base = j + 80 if ver == 0 else j + 92
                w, h = struct.unpack(">II", d[base:base + 8])
                w, h = w / 65536.0, h / 65536.0
                if w > 0 and h > 0 and (best is None or w * h > best[0] * best[1]):
                    best = (w, h)
            except Exception:
                pass
            pos = j + 4
        handlers = set()
        pos = 0
        while True:
            j = d.find(b"hdlr", pos)
            if j < 0:
                break
            try:
                if j + 16 <= len(d):
                    handlers.add(d[j + 12:j + 16].decode("latin1"))
            except Exception:
                pass
            pos = j + 4
        if "soun" in handlers:
            has_audio = True
        elif "vide" in handlers and _moov_complete(d):
            has_audio = False
        else:
            has_audio = None
        if best:
            return (int(best[0]), int(best[1]), has_audio)
        if has_audio is not None:
            return (0, 0, has_audio)
        return None
    except Exception:
        return None


def _probe_mp4_dims(url, timeout=4):
    """Purana wrapper (dims hi chahiye ho to) — audio flag chhod do."""
    try:
        r = _probe_mp4_tracks(url, timeout)
        if r and min(r[0], r[1]) > 0:
            return (r[0], r[1])
    except Exception:
        pass
    return None


def _fill_missing_heights(formats, limit=4):
    """Height-missing/guessed video entries (Facebook sd/hd) ki EXACT resolution
    probe karke label/height set karo + codec-unknown (FB/IG guess-progressive)
    entries me ASLI audio-track verify karo. Video-only nikle to merge-path
    par bhejo (audio-sahit), warna BINA AAWAZ download hota hai.
    SPEED: probes PARALLEL — sequential me 10-20s lag jata tha."""
    try:
        has_any_audio = any((x.get("type") == "audio" and x.get("url"))
                            for x in (formats or []))
    except Exception:
        has_any_audio = False
    targets = []
    for c in formats or []:
        try:
            if c.get("type") != "video":
                c.pop("_guessed", None)
                c.pop("_verify_audio", None)
                continue
            need_dims = (c.get("height") or 0) <= 0 or c.get("_guessed")
            need_audio = bool(c.get("_verify_audio"))
            if (not need_dims and not need_audio) \
                    or (c.get("ext") or "") not in ("mp4", "m4v", "mov"):
                c.pop("_guessed", None)
                c.pop("_verify_audio", None)
                continue
            u = c.get("url") or ""
            if not u.startswith("http"):
                c.pop("_guessed", None)
                c.pop("_verify_audio", None)
                continue
            if limit <= 0:
                c.pop("_guessed", None)
                c.pop("_verify_audio", None)
                continue
            limit -= 1
            targets.append((c, need_dims))
        except Exception:
            try:
                c.pop("_guessed", None)
                c.pop("_verify_audio", None)
            except Exception:
                pass
            continue
    if targets:
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=3) as ex:
                futs = {ex.submit(_probe_mp4_tracks, (c.get("url") or ""), 4): (c, nd)
                        for c, nd in targets}
                for fut, (c, need_dims) in futs.items():
                    try:
                        d = fut.result(timeout=5)
                    except Exception:
                        d = None
                    try:
                        if d:
                            w, h, ha = d
                            if need_dims and w and h and min(w, h) > 0:
                                c["height"] = min(w, h)
                                c["label"] = "%dp" % min(w, h)
                            # Video-only CONFIRM + alag audio maujood = merge-path
                            # (direct download BINA AAWAZ deta — yahi asli bug tha).
                            if c.pop("_verify_audio", None):
                                if ha is False and has_any_audio:
                                    c["progressive"] = False
                                    c["needs_merge"] = True
                                    c["one_tap"] = False
                        else:
                            c.pop("_verify_audio", None)
                        c.pop("_guessed", None)
                    except Exception:
                        try:
                            c.pop("_verify_audio", None)
                            c.pop("_guessed", None)
                        except Exception:
                            pass
        except Exception:
            for c, _nd in targets:
                try:
                    c.pop("_verify_audio", None)
                    c.pop("_guessed", None)
                except Exception:
                    pass
    try:
        formats.sort(key=lambda x: (1 if x.get("progressive") else 0,
                                    x.get("height") or 0, x.get("tbr") or 0),
                     reverse=True)
    except Exception:
        pass

# Ye protocols direct-download nahi hote (playlist/manifest hote hain).
# Inhe download karne par sirf .m3u8 text milta hai -> "failed" lagta hai.
BAD_PROTOCOLS = {
    "mhtml", "m3u8", "m3u8_native",
    "http_dash_segments", "rtmp", "rtsp", "f4m", "ism",
}


def _ig_msg():
    return ("Instagram ne login-wall lagaya hai. App me upar 'IG Login' button dabao, "
            "apne Instagram account se login karo — cookies apne aap save ho jayengi, "
            "phir dobara Get Video dabao. (Har user apne phone par ek baar login karega, "
            "cookies dalne ki zaroorat nahi.)")


def _fb_msg():
    return ("Ye Facebook video private hai ya login maang rahi hai. "
            "Public videos (SD+HD) BINA LOGIN ke chalti hain — link sahi hai to dobara try karo. "
            "Private video ho to app me 'FB Login' se apne account se login karo (ek baar), phir retry.")


def friendly(e):
    msg = str(e).strip()
    if msg.startswith("ERROR:"):
        msg = msg[6:].strip()
    low = msg.lower()
    if "snapchat" in low or "snapchat.com" in low:
        if "unsupported url" in low:
            return ("Ye Snapchat link support nahi hai. Sirf Spotlight links chalte hain "
                    "(snapchat.com/spotlight/...). Snapchat app me video kholo → Share → "
                    "Copy Link karke Spotlight link paste karo.")
        return ("Snapchat link nahi khula — Spotlight ka public link try karo "
                "(snapchat.com/spotlight/...). Private/story links supported nahi hain.")
    if "sign in to confirm" in low or "bot" in low:
        return "YouTube bot-check laga raha hai. Thodi der ruk kar retry karo."
    if "private" in low:
        return "Ye video private hai — sirf public videos download hoti hain."
    if "login required" in low or "log in" in low or "not logged in" in low:
        if "instagram" in low or "instagr.am" in low:
            return _ig_msg()
        if "facebook" in low or "fb" in low:
            return _fb_msg()
        return "Is video ke liye login chahiye — supported nahi hai."
    # Instagram login-wall ke ASLI roop (login cookies hi chahiye — verified:
    # Meta logged-out ko khaali page deta hai, embed me bhi video nahi).
    if ("empty media response" in low or "cookies-from-browser" in low
            or "--cookies" in low
            or ("cannot parse data" in low and ("instagram" in low or "instagr.am" in low))):
        if "instagram" in low or "instagr.am" in low:
            return _ig_msg()
        return "Is video ke liye login chahiye — supported nahi hai."
    # Facebook "cannot parse data" = page aadhi aayi (UA/variant issue), LOGIN NAHI.
    # (Verified: public FB videos sd+hd BINA LOGIN ke nikalte hain.)
    if "cannot parse data" in low and ("facebook" in low or "fb" in low):
        return ("Facebook page poori load nahi hui — dobara Get Video dabao. "
                "(Public videos bina login ke chalti hain.)")
    if "rate-limit" in low or "rate limited" in low or "try again later" in low:
        return "Site ne temporarily rok lagayi hai. 10-15 min ruk kar retry karo."
    if "playlist" in low and ("not exist" in low or "does not exist" in low
            or "not found" in low or "unavailable" in low or "empty" in low):
        return "Playlist nahi mili ya private hai — direct video ka link paste karo."
    if "reload" in low and "page" in low:
        return "Page load nahi hui — net check karke dobara Get Video dabao."
    if "unsupported url" in low:
        return "Ye URL supported nahi hai."
    if "timed out" in low or "timeout" in low:
        return "Site ne reply nahi diya. Net check karke retry karo."
    if "http error 403" in low or "403" in low:
        return "Link expire ho gaya (403). Dobara Get Video dabao taaki fresh link mile, phir download karo."
    return msg[:300]


def _codec_rank(vcodec):
    """Merge-compat ranking: avc1 (H.264) sabse safe -> vp9 -> av01 sabse risky."""
    v = (vcodec or "").lower()
    if v.startswith("avc1") or v.startswith("h264"):
        return 3
    if "vp9" in v or v.startswith("vp09"):
        return 2
    if "av01" in v or "av1" in v:
        return 1
    if v not in ("none", ""):
        return 2
    return 0


def pick_formats(formats_raw, duration):
    cands = []
    for f in formats_raw or []:
        fid = str(f.get("format_id") or "")
        proto = (f.get("protocol") or "").lower()
        url = f.get("url") or ""
        if not url:
            continue
        # HLS/DASH manifest ya storyboard -> direct download FAIL hoga, skip karo
        if proto in BAD_PROTOCOLS:
            continue
        if "manifest.googlevideo.com" in url:
            continue
        if f.get("protocol") == "mhtml":
            continue
        if ("storyboard" in fid or fid.startswith("sb")) and not f.get("height"):
            continue
        vcodec = f.get("vcodec") or "none"
        acodec = f.get("acodec") or "none"
        if vcodec == "unknown":
            vcodec = "none"
        if acodec == "unknown":
            acodec = "none"
        # raw codec unknown hai ya explicitly-none? (Instagram video_versions me
        # acodec=None = "pata nahi, lekin audio file me hi hai", jabki DASH
        # video-only me acodec="none" explicit hota hai.)
        raw_acodec_unknown = f.get("acodec") is None
        has_video = vcodec not in ("none", "")
        has_audio = acodec not in ("none", "")
        ext = (f.get("ext") or "").lower()
        if not ext:
            # EXT-INFER (Instagram video_versions me ext nahi aata): URL se nikalo.
            try:
                import re as _re
                _path = (url.split("?", 1)[0].rsplit("/", 1)[-1] or "")
                if "." in _path:
                    _e = _path.rsplit(".", 1)[-1].lower()
                    if _e in ("mp4", "m4v", "mov", "webm", "mkv", "m4a",
                              "mp3", "wav", "ogg", "opus", "flac", "aac"):
                        ext = _e
            except Exception:
                pass
        # SPARSE-FIX (Facebook sd/hd jaisi entries): codec/height bilkul nahi
        # aate (None), lekin https-mp4 progressive hota hai (video+audio ek file).
        # Inhe phenko mat — warna "sirf 360p" / "koi link nahi" dikhega.
        # (Verified: FB sd/hd BINA LOGIN progressive mp4 + audio ke saath.)
        verify_audio = False
        if not has_video and not has_audio and ext in ("mp4", "m4v", "mov") \
                and proto in ("https", "http"):
            has_video, has_audio = True, True
            vcodec, acodec = "avc1", "mp4a"
            verify_audio = True
        # ext ab bhi khaali ho aur URL Instagram/FB CDN ka ho to mp4 mano
        # (signed-URL me kabhi extension nahi hota, phir bhi progressive mp4 hai).
        if not ext and has_video and proto in ("https", "http"):
            try:
                _u = url.lower()
                if ".mp4" in _u or "instagram" in _u or "fbcdn" in _u or "scontent" in _u:
                    ext = "mp4"
            except Exception:
                pass
        # INSTAGRAM-FIX (video_versions direct-mp4): vcodec to hai (h264/avc),
        # lekin acodec=None (unknown) aata hai jabki audio FILE ME HI hota hai.
        # DASH video-only me acodec="none" EXPLICIT hota hai — wahan ye fix
        # lagna nahi chahiye. Sirf unknown-acodec + direct https-mp4 +
        # height/width wali entry ko progressive mano (audio-sahit, 1-tap).
        if has_video and not has_audio and raw_acodec_unknown \
                and ext in ("mp4", "m4v", "mov") and proto in ("https", "http") \
                and (f.get("height") or f.get("width")):
            has_audio = True
            acodec = "mp4a"
            verify_audio = True
        if not has_video and not has_audio:
            continue
        # MERGE-FAIL FIX: video-only AV1 (av01) phone ke MediaMuxer me judta hi
        # nahi (har device par incompatible) → list me dikhane ka matlab pakka
        # "merging fail" / video-only file. Isliye hatao; AVC/VP9 rahenge.
        _vlow = (vcodec or "").lower()
        if has_video and not has_audio and (_vlow.startswith("av01") or _vlow == "av1"):
            continue
        # aise entries jinka na video na audio codec ho (233/234 jaise) -> skip
        # (sparse https-mp4 upar already progressive maan liya gaya hai)
        if ext == "mp4" and not has_video and not has_audio:
            continue
        height = f.get("height")
        _guessed_h = False
        # Facebook sd/hd me height nahi aati — format-id fallback (probe baad me exact karega)
        if not height:
            fl = fid.lower()
            if fl == "hd":
                height, _guessed_h = 720, True
            elif fl == "sd":
                height, _guessed_h = 360, True
        abr = f.get("abr") or 0
        tbr = f.get("tbr") or 0
        filesize = f.get("filesize") or f.get("filesize_approx")
        if not filesize and tbr and duration:
            try:
                filesize = int(tbr * 1000 / 8 * duration)
            except Exception:
                filesize = None

        is_drc = fid.endswith("-drc") or "drc" in fid.lower()
        vrank = _codec_rank(vcodec) if has_video else 0
        # mp4/avc sabse compatible -> rank up
        container_rank = 1 if ext in ("mp4", "m4a") else 0

        if has_video and has_audio:
            ftype, progressive = "video", True
            label = "%dp" % height if height else (f.get("format_note") or ext or "Video")
        elif has_video:
            ftype, progressive = "video", False
            label = "%dp" % height if height else (f.get("format_note") or ext or "Video")
        else:
            ftype, progressive = "audio", False
            label = f.get("format_note") or ("Audio %dkbps" % int(abr) if abr else "Audio")

        cands.append({
            "format_id": fid, "ext": ext, "type": ftype,
            "label": label, "height": height or 0, "abr": abr,
            "filesize": filesize or 0, "tbr": tbr, "url": url,
            "progressive": progressive,
            # VidMate style 1-tap: progressive = video+audio juda, single download, merge-zero-fail
            "one_tap": bool(progressive),
            "needs_merge": bool(has_video and not has_audio),
            "vcodec": vcodec, "acodec": acodec, "protocol": proto,
            "_vrank": vrank, "_crank": container_rank,
            "_drc": 1 if is_drc else 0,
            "_guessed": 1 if _guessed_h else 0,
            "_verify_audio": 1 if (verify_audio and progressive) else 0,
        })

    # dedupe: har (type, height, progressive, container-family) me best rakho.
    # Isse har height par mp4-avc (merge-safe) + best-quality dono milte hain.
    best = {}
    for c in cands:
        if c["type"] == "video":
            fam = "mp4" if c["ext"] in ("mp4", "m4v") else c["ext"]
            key = ("v", c["height"], c["progressive"], fam)
        else:
            key = ("a", int((c["abr"] or 0) // 32), c["ext"])
        old = best.get(key)
        score = (c["_vrank"], c["_crank"], -c["_drc"], c["tbr"] or 0)
        oscore = (old["_vrank"], old["_crank"], -old["_drc"], old["tbr"] or 0) if old else None
        if old is None or score > oscore:
            best[key] = c
    formats = list(best.values())
    # har height par max 2 video rakho (mp4-safe + best) taaki list chhoti rahe
    by_h = {}
    for c in formats:
        if c["type"] == "video":
            by_h.setdefault(c["height"], []).append(c)
    keep_ids = set()
    for h, lst in by_h.items():
        lst.sort(key=lambda x: (x["_vrank"], x["_crank"], -x["_drc"], x["tbr"] or 0), reverse=True)
        for c in lst[:2]:
            keep_ids.add(id(c))
    formats = [c for c in formats if c["type"] != "video" or id(c) in keep_ids]
    for c in formats:
        for k in ("_vrank", "_crank", "_drc"):
            c.pop(k, None)
    # sort: progressive (bina merge wale) pehle taaki 1-tap download mile,
    # phir height desc. Merge wale baad me (HD Merge button).
    def _sort(c):
        return (1 if c.get("progressive") else 0, c.get("height") or 0, c.get("tbr") or 0)
    formats.sort(key=_sort, reverse=True)
    return formats[:60]


def extract(url, cookiefile=""):
    """Hamesha JSON string return karta hai (Kotlin ke liye safe).

    cookiefile: Instagram/Facebook login cookies ka path (Netscape format).
    Empty ya missing ho to bina cookies ke try hota hai.
    """
    try:
        return json.dumps(_extract(url, cookiefile), ensure_ascii=False)
    except Exception as e:  # noqa: BLE001
        return json.dumps({"error": friendly(e)}, ensure_ascii=False)


def search(query, limit=12):
    """YouTube search (Watch-tab wali search bar ke liye). Hamesha JSON string.

    API key nahi chahiye — yt-dlp ka ytsearch istemal hota hai.
    Returns {results:[{id,title,url,thumbnail,duration}], count:n}.
    """
    try:
        return json.dumps(_search(query, limit), ensure_ascii=False)
    except Exception as e:  # noqa: BLE001
        return json.dumps({"error": friendly(e)}, ensure_ascii=False)


def _search(query, limit=12):
    q = (query or "").strip()
    if not q:
        return {"error": "Search text khaali hai."}
    try:
        limit = max(1, min(int(limit or 12), 25))
    except Exception:
        limit = 12
    fopts = {
        "quiet": True, "no_warnings": True, "skip_download": True,
        "extract_flat": True,
        "socket_timeout": 10,
        # NOTE: custom User-Agent lagane par YouTube search KHAALI milta hai —
        # isliye search me yt-dlp ka default UA hi rehne do (extract wala nahi).
    }
    try:
        with yt_dlp.YoutubeDL(fopts) as ydl:
            info = ydl.extract_info("ytsearch%d:%s" % (limit, q), download=False)
    except Exception as e:  # noqa: BLE001
        return {"error": friendly(e)}
    out = []
    try:
        for e in (info.get("entries") or [])[:limit]:
            if not isinstance(e, dict):
                continue
            vid = e.get("id") or ""
            if not vid:
                continue
            url = e.get("url") or e.get("webpage_url") or ""
            if not url or not url.startswith("http"):
                url = "https://www.youtube.com/watch?v=" + vid
            title = e.get("title") or vid
            thumb = ""
            try:
                ths = e.get("thumbnails") or []
                if ths:
                    thumb = (ths[-1] or {}).get("url") or ""
            except Exception:
                pass
            if not thumb:
                thumb = "https://i.ytimg.com/vi/%s/hqdefault.jpg" % vid
            try:
                dur = int(e.get("duration") or 0)
            except Exception:
                dur = 0
            try:
                views = int(e.get("view_count") or 0)
            except Exception:
                views = 0
            out.append({"id": vid, "title": title, "url": url,
                        "thumbnail": thumb, "duration": dur,
                        "channel": e.get("channel") or e.get("uploader") or "",
                        "views": views})
    except Exception:
        pass
    return {"results": out, "count": len(out)}


# YouTube web client ki public key (youtubei API ke liye — app jaisi key).
YT_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"


def _dur_to_sec(t):
    try:
        s = 0
        for p in str(t).strip().split(":"):
            s = s * 60 + int(p)
        return s
    except Exception:
        return 0


def _parse_lockup(r):
    """Naya YouTube format (lockupViewModel) -> Up-Next item. Video hi rakho."""
    try:
        lv = r.get("lockupViewModel") or {}
        if (lv.get("contentType") or "") != "LOCKUP_CONTENT_TYPE_VIDEO":
            return None
        vid = lv.get("contentId") or ""
        if not vid:
            return None
        md = ((lv.get("metadata") or {}).get("lockupMetadataViewModel") or {})
        title = (md.get("title") or {}).get("content") or vid
        thumb, dur = "", 0
        try:
            ci = (lv.get("contentImage") or {}).get("thumbnailViewModel") or {}
            srcs = ((ci.get("image") or {}).get("sources") or [])
            if srcs:
                thumb = (srcs[-1] or {}).get("url") or ""
            for ov in (ci.get("overlays") or []):
                try:
                    badges = ((ov.get("thumbnailBottomOverlayViewModel") or {}).get("badges")) or []
                    for b in badges:
                        t = ((b.get("thumbnailBadgeViewModel") or {}).get("text")) or ""
                        if t and ":" in t:
                            dur = _dur_to_sec(t)
                            break
                    if dur:
                        break
                except Exception:
                    continue
        except Exception:
            pass
        if not thumb:
            thumb = "https://i.ytimg.com/vi/%s/hqdefault.jpg" % vid
        return {"id": vid, "title": title,
                "url": "https://www.youtube.com/watch?v=" + vid,
                "thumbnail": thumb, "duration": dur}
    except Exception:
        return None


def _parse_compact_video(r):
    """Purana YouTube format (compactVideoRenderer) -> Up-Next item."""
    try:
        cv = r.get("compactVideoRenderer") or {}
        vid = cv.get("videoId") or ""
        if not vid:
            return None
        title = ""
        try:
            t = cv.get("title") or {}
            title = t.get("simpleText") or "".join(
                [(x.get("text") or "") for x in (t.get("runs") or [])])
        except Exception:
            pass
        thumb = ""
        try:
            ths = ((cv.get("thumbnail") or {}).get("thumbnails") or [])
            if ths:
                thumb = (ths[-1] or {}).get("url") or ""
        except Exception:
            pass
        if not thumb:
            thumb = "https://i.ytimg.com/vi/%s/hqdefault.jpg" % vid
        dur = 0
        try:
            lt = (cv.get("lengthText") or {}).get("simpleText") or ""
            if lt:
                dur = _dur_to_sec(lt)
        except Exception:
            pass
        return {"id": vid, "title": title or vid,
                "url": "https://www.youtube.com/watch?v=" + vid,
                "thumbnail": thumb, "duration": dur}
    except Exception:
        return None


def _youtube_related(video_id, limit=15):
    """Single YouTube video ke Related/Up-Next videos (YouTube internal API se).

    yt-dlp ye list nahi deta — dusre downloader apps bhi isi internal API se
    nikalte hain, isliye single video par bhi Up-Next dikhta hai.
    Fail ho to [] — main video par koi asar nahi.
    """
    import urllib.request
    try:
        vid = (video_id or "").strip()
        if not vid or len(vid) > 32:
            return []
        payload = json.dumps({
            "context": {"client": {"clientName": "WEB", "clientVersion": "2.20241201",
                                   "hl": "en", "gl": "US"}},
            "videoId": vid,
        }).encode("utf-8")
        req = urllib.request.Request(
            "https://www.youtube.com/youtubei/v1/next?key=" + YT_API_KEY + "&prettyPrint=false",
            data=payload,
            headers={"Content-Type": "application/json", "User-Agent": UA})
        with urllib.request.urlopen(req, timeout=6) as res:
            data = json.loads(res.read().decode("utf-8", "replace"))
        sec = ((((data.get("contents") or {}).get("twoColumnWatchNextResults") or {})
                .get("secondaryResults") or {}).get("secondaryResults") or {})
        out, seen = [], set([vid])
        for r in (sec.get("results") or []):
            if not isinstance(r, dict) or len(out) >= limit:
                break
            if "lockupViewModel" in r:
                item = _parse_lockup(r)
            elif "compactVideoRenderer" in r:
                item = _parse_compact_video(r)
            else:
                item = None
            if item and item["id"] not in seen:
                seen.add(item["id"])
                out.append(item)
        return out
    except Exception:
        return []


def _looks_like_playlist(url):
    u = (url or "").lower()
    return ("list=" in u or "/playlist" in u or "/channel/" in u or "/@ " in u
            or "/@" in u or "/c/" in u or "/user/" in u or "youtube.com/feed" in u)


def _flat_playlist_entries(page_url, cookiefile="", limit=20):
    """Up-Next ke liye halki playlist entries (bina formats, fast).
    Fail ho to [] — main video par koi asar nahi."""
    try:
        fopts = {
            "quiet": True,
            "no_warnings": True,
            "socket_timeout": 8,
            "retries": 1,
            "extractor_retries": 1,
            "http_headers": {"User-Agent": UA},
            "extract_flat": True,
            "playlistend": limit,
            "geo_bypass": True,
        }
        try:
            if cookiefile and os.path.isfile(cookiefile) and os.path.getsize(cookiefile) > 2:
                fopts["cookiefile"] = cookiefile
        except Exception:
            pass
        with yt_dlp.YoutubeDL(fopts) as ydl:
            pinfo = ydl.extract_info(page_url, download=False)
        if not pinfo:
            return [], "", 0
        if pinfo.get("_type") != "playlist":
            return [], "", 0
        out = []
        for e in (pinfo.get("entries") or [])[:limit]:
            if not e:
                continue
            vid = e.get("id") or ""
            title = e.get("title") or vid
            wurl = e.get("webpage_url") or e.get("url") or ""
            if wurl and wurl.startswith("/"):
                wurl = "https://www.youtube.com" + wurl
            if not wurl and vid and (pinfo.get("extractor") or "").lower().startswith("youtube"):
                wurl = "https://www.youtube.com/watch?v=" + vid
            thumbs = e.get("thumbnails") or []
            thumb = ""
            try:
                if thumbs:
                    thumb = (thumbs[-1] or {}).get("url") or ""
            except Exception:
                thumb = ""
            if not thumb:
                thumb = e.get("thumbnail") or ""
            if vid and "youtube" in (pinfo.get("extractor") or "").lower() and not thumb:
                thumb = "https://i.ytimg.com/vi/%s/hqdefault.jpg" % vid
            out.append({
                "id": vid,
                "title": title,
                "url": wurl or page_url,
                "thumbnail": thumb,
                "duration": e.get("duration") or 0,
            })
        return out, (pinfo.get("title") or ""), (pinfo.get("playlist_count") or len(out))
    except Exception:
        return [], "", 0


def _normalize_snapchat_url(url):
    """Snapchat /t/ short-links redirect hokar asli Spotlight page par jate
    hain. yt-dlp sirf spotlight/<id> samajhta hai — redirect resolve karke
    final URL do. Sirf /t/ par network call (5s cap); baaki turant wapas —
    yt-dlp fast-fail karega aur friendly() sahi message dega.
    Fail-soft: original wapas."""
    try:
        import re as _re
        import urllib.request as _ur
        low = (url or "").lower()
        if "snapchat.com" not in low and "snapchat" not in low:
            return url
        if _re.search(r"snapchat\.com/spotlight/\w+", url, _re.I):
            return url
        if _re.search(r"snapchat\.com/t/", url, _re.I):
            req = _ur.Request(url, headers={"User-Agent": UA})
            with _ur.urlopen(req, timeout=5) as res:
                final = res.geturl() or url
            if final and final != url:
                return final
    except Exception:
        pass
    return url


def _extract(url, cookiefile=""):
    url = (url or "").strip()
    if not url.startswith(("http://", "https://")):
        return {"error": "URL 'http://' ya 'https://' se shuru hona chahiye."}
    # Snapchat short-link ho to redirect resolve karke asli page nikalo
    try:
        url = _normalize_snapchat_url(url)
    except Exception:
        pass
    # Snapchat: yt-dlp SIRF spotlight/<id> samajhta hai. Baaki pattern par
    # network par bhej kar 8-10s latkane se achha turant saaf jawab do.
    try:
        low = url.lower()
        if "snapchat.com" in low and not re.search(
                r"snapchat\.com/spotlight/\w+", url, re.I):
            return {"error": (
                "Ye Snapchat link support nahi hai. Sirf Spotlight links chalte hain "
                "(snapchat.com/spotlight/...). Snapchat app me video kholo → Share → "
                "Copy Link karke Spotlight link paste karo.")}
    except Exception:
        pass

    opts = {
        "quiet": True,
        "no_warnings": True,
        # FAST-FAIL: phone par extract latakna nahi chahiye (scanning-stuck fix).
        # Pehle 30s x retries=3 x extractor_retries=3 = 4+ min worst-case tha,
        # isliye UI 90s timeout ke baad bhi background thread me atka rehta tha
        # aur agli search uske peeche queue ho jati thi ("bas scanning hota rehta").
        # Ab worst-case ~15s x 2 x 2 = ~60s se kam, playlist sirf pehla video.
        # NOTE: success path 1 attempt me nikalta hai — retry/timeout values usey
        # slow nahi karte; ye sirf fail-fast ke liye hain.
        "socket_timeout": 10,
        "retries": 2,
        "fragment_retries": 2,
        "extractor_retries": 1,
        "http_headers": {"User-Agent": _ua_for(url)},
        "no_playlist": False,
        # PURANA-APK FIX: default clients (visionos/web, SABR) ZERO progressive
        # (single-file) links dete hain — sab video-only, har quality me merge.
        # Plain "android" client jodne se 18 (360p mp4, video+audio juda) wapas
        # milta hai = purane APK jaisa fast direct download, no merging.
        # HD (720p+) YouTube par single-file deta hi nahi → wahan 1-tap hidden-merge.
        "extractor_args": {"youtube": {"player_client": ["default", "android"]}},
        # Playlist/channel link aaye to poori list enumerate mat karo (bahut slow) —
        # sirf pehla video nikalo (Kotlin side playlist-error nahi, instant result).
        "playlist_items": "1",
        "playlistend": 1,
        "geo_bypass": True,
    }
    # Instagram/Facebook login-wall bypass ke liye cookies (bundled cookies.txt se).
    # File na ho to skip — public videos bina cookies ke nikalti hain.
    try:
        if cookiefile and os.path.isfile(cookiefile) and os.path.getsize(cookiefile) > 2:
            opts["cookiefile"] = cookiefile
    except Exception:
        pass
    # Repeat URL = turant (memory cache, 3 min) — Recent reopen / double-tap
    # Get Video par yt-dlp dobara network par nahi jata.
    ckey = _cache_key(url, cookiefile)
    try:
        hit = _cache_get(ckey)
        if isinstance(hit, dict) and not hit.get("error"):
            return hit
    except Exception:
        pass

    # SPEED: flat-playlist + related-videos main extract ke SAATH parallel —
    # pehle ye sequential the (2-3 extra round-trip = kayi second).
    flat_box, rel_box = {}, {}
    flat_thread = None
    if _looks_like_playlist(url):
        def _do_flat():
            try:
                flat_box["r"] = _flat_playlist_entries(url, cookiefile, 20)
            except Exception:
                flat_box["r"] = ([], "", 0)
        try:
            flat_thread = threading.Thread(target=_do_flat, daemon=True)
            flat_thread.start()
        except Exception:
            flat_thread = None
    spec_vid = ""
    try:
        ul = url.lower()
        if "youtube.com" in ul or "youtu.be" in ul:
            spec_vid = _parse_youtube_id(url)
    except Exception:
        spec_vid = ""
    rel_thread = None
    if spec_vid:
        def _do_rel():
            try:
                rel_box["r"] = _youtube_related(spec_vid, 15)
            except Exception:
                rel_box["r"] = []
        try:
            rel_thread = threading.Thread(target=_do_rel, daemon=True)
            rel_thread.start()
        except Exception:
            rel_thread = None

    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)

    if not info:
        return {"error": "Video info nahi mila."}

    # Up-Next: speculative flat-thread ka result uthao (pehle se chal raha tha)
    playlist_entries, playlist_title, playlist_count = [], "", 0
    playlist_index = 0
    if flat_thread is not None:
        try:
            flat_thread.join(timeout=20)
        except Exception:
            pass
        try:
            playlist_entries, playlist_title, playlist_count = flat_box.get("r") or ([], "", 0)
        except Exception:
            playlist_entries, playlist_title, playlist_count = [], "", 0
    elif _looks_like_playlist(url):
        try:
            playlist_entries, playlist_title, playlist_count = _flat_playlist_entries(
                url, cookiefile, 20)
        except Exception:
            playlist_entries, playlist_title, playlist_count = [], "", 0

    if info.get("_type") == "playlist":
        entries = [e for e in (info.get("entries") or []) if e]
        if not entries:
            return {"error": "Playlist khaali hai ya readable nahi hai."}
        info = entries[0]
        if not info.get("formats") and (info.get("webpage_url") or info.get("url")):
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(info.get("webpage_url") or info["url"], download=False)

    # Single YouTube video ho (playlist entries nahi mili) to Related videos
    # nikalo — speculative thread pehle se chal raha tha, bas result uthao.
    if not playlist_entries:
        try:
            ext = (info.get("extractor") or "").lower()
            if "youtube" in ext and info.get("id"):
                rel = None
                if rel_thread is not None and spec_vid and spec_vid == info.get("id"):
                    try:
                        rel_thread.join(timeout=10)
                    except Exception:
                        pass
                    try:
                        rel = rel_box.get("r") or []
                    except Exception:
                        rel = []
                if not rel:
                    rel = _youtube_related(info.get("id"), 15)
                if rel:
                    playlist_entries, playlist_title, playlist_count = (
                        rel, "Related videos", len(rel))
        except Exception:
            pass

    duration = info.get("duration") or 0
    formats = pick_formats(info.get("formats"), duration)
    # HLS-only sites (1600+ wada): m3u8 entries alag rakho — server ffmpeg se mp4
    hls_list = []
    try:
        for f in info.get("formats") or []:
            proto = (f.get("protocol") or "").lower()
            u = f.get("url") or ""
            if not u:
                continue
            if proto in ("m3u8", "m3u8_native") or u.endswith(".m3u8") or ".m3u8?" in u:
                h = f.get("height") or 0
                note = f.get("format_note") or ("%dp" % h if h else "HLS")
                hls_list.append({
                    "format_id": str(f.get("format_id") or "hls"),
                    "ext": "mp4", "label": note, "height": h,
                    "url": u, "tbr": f.get("tbr") or 0,
                })
                if len(hls_list) >= 10:
                    break
    except Exception:
        hls_list = []
    if not formats and info.get("url"):
        # DIRECT-FIX: direct file link (jaise koi .mp4 URL) par yt-dlp kabhi
        # "formats" list nahi deta — seedha top-level "url" deta hai. Pehle ye
        # case "Koi direct-download link nahi mila" error me girta tha.
        u = (info.get("url") or "").strip()
        if u and "manifest.googlevideo.com" not in u:
            ext = (info.get("ext") or "mp4").lower()
            formats = [{
                "format_id": "direct", "ext": ext, "type": "video",
                "label": info.get("format_note") or "Direct",
                "height": info.get("height") or 0, "abr": info.get("abr") or 0,
                "filesize": info.get("filesize") or info.get("filesize_approx") or 0,
                "tbr": info.get("tbr") or 0, "url": u,
                "progressive": True, "one_tap": True, "needs_merge": False,
                "vcodec": info.get("vcodec") or "",
                "acodec": info.get("acodec") or "",
                "protocol": info.get("protocol") or "https",
            }]
    if not formats:
        if hls_list:
            # HLS-only site: direct nahi, par 🌐 Server se mp4 ban sakta hai
            resp = {
                "title": info.get("title") or "Video",
                "duration": duration,
                "author": info.get("uploader") or info.get("channel") or "",
                "source": info.get("extractor") or "",
                "thumbnail": info.get("thumbnail") or "",
                "webpage_url": info.get("webpage_url") or url,
                "formats": [],
                "best_audio": None,
                "best_audio_mp4": None,
                "best_audio_webm": None,
                "preview_url": "",
                "preview_ext": "",
                "preview_has_audio": False,
                "hls": hls_list,
                "hls_only": True,
                "playlist": playlist_entries,
                "playlist_title": playlist_title,
                "playlist_count": playlist_count,
                "playlist_index": playlist_index,
            }
            _cache_put(ckey, resp)
            return resp
        return {"error": "Koi direct-download link nahi mila. Ye video HLS-only/private ho sakta hai — dusri quality ya video try karo."}

    # Facebook sd/hd jaisi entries ki EXACT resolution probe karo (label sahi aaye)
    try:
        _fill_missing_heights(formats)
    except Exception:
        pass

    aud = [f for f in formats if f["type"] == "audio"]
    # merge-compat audio: mp4-video ke liye m4a, webm-video ke liye opus/webm
    aud_mp4 = sorted(
        [f for f in aud if f["ext"] in ("m4a", "mp4")],
        key=lambda x: (x["abr"], x["tbr"]), reverse=True)
    aud_webm = sorted(
        [f for f in aud if f["ext"] in ("webm", "weba", "opus")],
        key=lambda x: (x["abr"], x["tbr"]), reverse=True)
    aud_all = sorted(aud, key=lambda x: (x["abr"], x["tbr"]), reverse=True)

    def _mini(f):
        return {"url": f["url"], "ext": f["ext"] or "m4a", "abr": f["abr"]} if f else None

    best_audio = _mini(aud_all[0]) if aud_all else None

    # preview: sabse CHHOTA AVC-mp4 video (halka + har phone par smooth).
    # prog[0] mat lo — formats best-first sort hote hain, prog[0] sabse BADA hota hai.
    # NOTE: YouTube ab progressive (video+audio juda) links nahi deta — sab video-only
    # hain, isliye preview video-only hota hai + ExoPlayer usme m4a audio jod kar bajata hai.
    # VP9/AV1 chhota hone par bhi kamzor phone par atak sakta hai, isliye AVC prefer karo.
    prog = [f for f in formats if f.get("progressive")]
    preview = min(prog, key=lambda x: (x["height"], x["tbr"])) if prog else None
    if preview is None:
        vids = sorted([f for f in formats if f["type"] == "video"],
                      key=lambda x: (x["height"], x["tbr"]))
        # AVC-mp4 sabse compatible: pehle wahi dhoondo (instant smooth preview)
        avc = [v for v in vids
               if (v.get("vcodec") or "").lower().startswith(("avc1", "h264"))
               and v.get("ext") in ("mp4", "m4v")]
        preview = (avc[0] if avc else (vids[0] if vids else None))

    resp = {
        "title": info.get("title") or "Video",
        "duration": duration,
        "author": info.get("uploader") or info.get("channel") or "",
        "source": info.get("extractor") or "",
        "thumbnail": info.get("thumbnail") or "",
        "webpage_url": info.get("webpage_url") or url,
        "channel_id": info.get("channel_id") or "",
        "channel_url": info.get("channel_url") or info.get("uploader_url") or "",
        "formats": formats,
        "best_audio": best_audio,
        "best_audio_mp4": _mini(aud_mp4[0]) if aud_mp4 else best_audio,
        "best_audio_webm": _mini(aud_webm[0]) if aud_webm else best_audio,
        "preview_url": preview["url"] if preview else "",
        "preview_ext": preview["ext"] if preview else "",
        "preview_has_audio": bool(preview.get("progressive")) if preview else False,
        "hls": hls_list,
        "hls_only": False,
        # Preview me hi Next video (bina link copy): playlist ho to entries,
        # single video ho to khaali — app Recent-history se Up-Next banayega.
        "playlist": playlist_entries,
        "playlist_title": playlist_title,
        "playlist_count": playlist_count,
        "playlist_index": playlist_index,
    }
    _cache_put(ckey, resp)
    return resp
