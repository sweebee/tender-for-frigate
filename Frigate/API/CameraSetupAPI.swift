import Foundation

// Adding a camera as Frigate's own wizard does (0.17+): find its streams over ONVIF or from a
// brand's address, test each with ffprobe, then write the camera and its go2rtc streams.

/// What ffprobe found in a stream.
struct StreamProbe: Hashable {
    var width: Int?
    var height: Int?
    var videoCodec: String?
    var audioCodec: String?
    var fps: Double?

    var resolution: String? { width.flatMap { w in height.map { "\(w)×\($0)" } } }
    var isHEVC: Bool { ["hevc", "h265"].contains(videoCodec ?? "") }
    var pixels: Int { (width ?? 0) * (height ?? 0) }

    /// "2560×1440 · h265 · 20 fps · aac"
    var summary: String {
        [resolution, videoCodec.map { $0 == "hevc" ? "h265" : $0 }, fps.map { "\(Int($0.rounded())) fps" }, audioCodec]
            .compactMap { $0 }.joined(separator: " · ")
    }
}

/// What ONVIF told about the camera.
struct OnvifDevice: Hashable {
    var manufacturer: String
    var model: String
    var firmware: String
    var profiles: Int
    var ptz: Bool
    var panTilt: Bool
    /// Stream addresses the camera reported (credentials filled in by Frigate).
    var streams: [String]
}

enum CameraBrand: String, CaseIterable, Identifiable {
    case hikvision, dahua, reolink, ubiquiti, axis, tplink, foscam, other
    var id: String { rawValue }

    var title: String {
        switch self {
        case .hikvision: return "Hikvision"
        case .dahua: return "Dahua / Amcrest"
        case .reolink: return "Reolink"
        case .ubiquiti: return "Ubiquiti"
        case .axis: return "Axis"
        case .tplink: return "TP-Link / Tapo"
        case .foscam: return "Foscam"
        case .other: return L("Other address")
        }
    }

    /// The main stream's address, as Frigate's wizard builds it. Reolink: RTSP and HTTP-FLV.
    func urls(host: String, user: String, password: String) -> [String] {
        let templates: [String]
        switch self {
        case .hikvision: templates = ["rtsp://{u}:{p}@{h}:554/Streaming/Channels/101"]
        case .dahua: templates = ["rtsp://{u}:{p}@{h}:554/cam/realmonitor?channel=1&subtype=0"]
        case .ubiquiti: templates = ["rtsp://{u}:{p}@{h}:554/live/ch0"]
        case .axis: templates = ["rtsp://{u}:{p}@{h}:554/axis-media/media.amp"]
        case .tplink: templates = ["rtsp://{u}:{p}@{h}:554/stream1"]
        case .foscam: templates = ["rtsp://{u}:{p}@{h}:88/videoMain"]
        case .reolink: templates = ["http://{h}/flv?port=1935&app=bcs&stream=channel0_main.bcs&user={u}&password={p}",
                                    "rtsp://{u}:{p}@{h}:554/Preview_01_main"]
        case .other: templates = []
        }
        let u = user.addingPercentEncoding(withAllowedCharacters: .urlUserAllowed) ?? user
        let p = password.addingPercentEncoding(withAllowedCharacters: .urlPasswordAllowed) ?? password
        return templates.map { $0.replacingOccurrences(of: "{u}", with: u).replacingOccurrences(of: "{p}", with: p)
            .replacingOccurrences(of: "{h}", with: host) }
    }

    /// The sub stream next to the main one, where the brand has a known address for it.
    func subURL(main: String) -> String? {
        switch self {
        case .hikvision: return main.replacingOccurrences(of: "/Channels/101", with: "/Channels/102")
        case .dahua: return main.replacingOccurrences(of: "subtype=0", with: "subtype=1")
        case .reolink: return main.contains("channel0_main") ? main.replacingOccurrences(of: "channel0_main", with: "channel0_sub")
                                                               : main.replacingOccurrences(of: "Preview_01_main", with: "Preview_01_sub")
        case .tplink: return main.replacingOccurrences(of: "/stream1", with: "/stream2")
        default: return nil
        }
    }
}

extension FrigateClient {
    func probeOnvif(host: String, port: Int, user: String, password: String) async throws -> OnvifDevice {
        var req = URLRequest(url: url("api/onvif/probe", query: [
            "host": host, "port": String(port), "username": user, "password": password, "test": "false", "auth_type": "basic",
        ]))
        req.timeoutInterval = 35
        let obj = try JSONSerialization.jsonObject(with: try await performPublic(req)) as? [String: Any] ?? [:]
        guard obj["success"] as? Bool != false else { throw FrigateError.http(400, obj["message"] as? String) }
        let candidates = (obj["rtsp_candidates"] as? [[String: Any]] ?? [])
        let reported = candidates.filter { ($0["source"] as? String) == "GetStreamUri" }.compactMap { $0["uri"] as? String }
        func s(_ k: String) -> String { (obj[k] as? String).flatMap { $0 == "Unknown" ? nil : $0 } ?? "" }
        return OnvifDevice(manufacturer: s("manufacturer"), model: s("model"), firmware: s("firmware_version"),
                           profiles: (obj["profiles_count"] as? NSNumber)?.intValue ?? 0,
                           ptz: obj["ptz_supported"] as? Bool ?? false, panTilt: obj["pan_tilt_supported"] as? Bool ?? false,
                           streams: Array(NSOrderedSet(array: reported)) as? [String] ?? reported)
    }

    /// Reolink: "http-flv" up to 5 MP, "rtsp" above, nil when it cannot tell.
    func reolinkProtocol(host: String, user: String, password: String) async -> String? {
        guard let data = try? await self.data("api/reolink/detect", query: ["host": host, "username": user, "password": password], timeout: 15),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return obj["protocol"] as? String
    }

    /// Opens the stream on the server with ffprobe. Throws with ffprobe's (credential-free) error.
    func probeStream(_ streamURL: String) async throws -> StreamProbe {
        let data = try await self.data("api/ffprobe", query: ["paths": streamURL, "detailed": "true"], timeout: 20)
        guard let first = (try JSONSerialization.jsonObject(with: data) as? [[String: Any]])?.first else { throw FrigateError.notFrigate }
        if (first["return_code"] as? NSNumber)?.intValue ?? 0 != 0 {
            let lines = (first["stderr"] as? [String]) ?? [(first["stderr"] as? String) ?? ""]
            throw StreamProbeError(detail: lines.filter { !$0.isEmpty }.joined(separator: " "))
        }
        let streams = ((first["stdout"] as? [String: Any])?["streams"] as? [[String: Any]]) ?? []
        var probe = StreamProbe()
        for s in streams {
            let codec = (s["codec_name"] as? String)?.lowercased() ?? ""
            if probe.videoCodec == nil, ["h264", "hevc", "h265", "mjpeg"].contains(codec) {
                probe.videoCodec = codec
                probe.width = (s["width"] as? NSNumber)?.intValue
                probe.height = (s["height"] as? NSNumber)?.intValue
                if let rate = s["avg_frame_rate"] as? String {
                    let parts = rate.split(separator: "/").compactMap { Double($0) }
                    if parts.count == 2, parts[1] > 0 { probe.fps = parts[0] / parts[1] }
                }
            } else if probe.audioCodec == nil, ["aac", "mp3", "pcm_mulaw", "pcm_alaw", "opus"].contains(codec) {
                probe.audioCodec = codec
            }
        }
        guard probe.width != nil else { throw FrigateError.http(400, L("The stream has no video Frigate can use.")) }
        return probe
    }

    func streamSnapshotURL(_ streamURL: String) -> URL { url("api/ffprobe/snapshot", query: ["url": streamURL]) }

    /// Adds a stream to the running go2rtc (it reads its config only at start).
    func addGo2rtcStream(_ name: String, source: String) async throws {
        var req = URLRequest(url: url("api/go2rtc/streams/\(name)", query: ["src": source]))
        req.httpMethod = "PUT"
        req.setValue("1", forHTTPHeaderField: "X-CSRF-TOKEN")
        _ = try await performPublic(req)
    }
}

/// ffprobe's failure in words people can act on; its own text stays available.
struct StreamProbeError: LocalizedError {
    let detail: String
    var errorDescription: String? {
        let d = detail.lowercased()
        if d.contains("401") || d.contains("unauthorized") { return L("The camera refused the login. Check the username and password.") }
        if d.contains("404") || d.contains("not found") { return L("The camera has no stream at this address.") }
        if d.contains("refused") || d.contains("timed out") || d.contains("timeout") || d.contains("no route") || d.contains("connection to") {
            return L("The camera does not answer at this address.")
        }
        return L("Frigate could not open this stream.")
    }
}
