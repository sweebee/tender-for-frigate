import Foundation

/// A camera's stream address ("rtsp://user:password@192.168.1.50:554/stream", or Reolink's
/// "http://…/flv?…&user=…&password=…"): its host and login read and replaced as text, so the
/// rest stays as written, {FRIGATE_*} variables included.
enum StreamAddress {
    /// Shown instead of the password.
    static let mask = "••••"

    private struct Parts {
        var scheme: String
        var userinfo: String?
        var host: String
        var port: String?
        var rest: String
    }

    private static let pattern = #/^(?<scheme>[A-Za-z][A-Za-z0-9+.\-]*)://(?:(?<userinfo>[^/\s]*)@)?(?<host>\[[^\]\s]+\]|[^:/?#@\s]+)(?::(?<port>\d+))?(?<rest>.*)$/#
    private static let queryUser = #/(?i)([?&](?:user|username)=)([^&#\s]*)/#
    private static let queryPassword = #/(?i)([?&](?:password|pass|pwd)=)([^&#\s]*)/#

    private static func parts(_ url: String) -> Parts? {
        guard let m = url.wholeMatch(of: pattern) else { return nil }
        return Parts(scheme: String(m.scheme), userinfo: m.userinfo.map(String.init), host: String(m.host),
                     port: m.port.map(String.init), rest: String(m.rest))
    }

    private static func join(_ p: Parts) -> String {
        p.scheme + "://" + (p.userinfo.map { $0 + "@" } ?? "") + p.host + (p.port.map { ":" + $0 } ?? "") + p.rest
    }

    static func host(_ url: String) -> String? { parts(url)?.host }

    /// A go2rtc restream Frigate reads (rtsp://127.0.0.1:8554/<name>): its name.
    static func restreamName(_ path: String) -> String? {
        guard let p = parts(path), ["127.0.0.1", "localhost"].contains(p.host), p.port == "8554" else { return nil }
        let name = p.rest.dropFirst().prefix { $0 != "?" && $0 != "#" && $0 != "/" }
        return name.isEmpty ? nil : String(name)
    }

    static func isLocal(_ url: String) -> Bool { ["127.0.0.1", "localhost"].contains(host(url) ?? "") }

    static func user(_ url: String) -> String? {
        if let info = parts(url)?.userinfo, !info.isEmpty {
            let u = String(info.prefix { $0 != ":" })
            return u.removingPercentEncoding ?? u
        }
        if let m = url.firstMatch(of: queryUser) { return String(m.2).removingPercentEncoding ?? String(m.2) }
        return nil
    }

    /// The password as written in the address (percent-encoded, or a {FRIGATE_*} variable).
    static func password(_ url: String) -> String? {
        if let info = parts(url)?.userinfo, let colon = info.firstIndex(of: ":") { return String(info[info.index(after: colon)...]) }
        if let m = url.firstMatch(of: queryPassword) { return String(m.2) }
        return nil
    }

    static func masked(_ url: String) -> String {
        var s = url
        if var p = parts(s), let info = p.userinfo, let colon = info.firstIndex(of: ":"), info.index(after: colon) < info.endIndex {
            p.userinfo = info[...colon] + mask
            s = join(p)
        }
        return s.replacing(queryPassword) { $0.1 + mask }
    }

    /// The edited text of a masked address with the original password back in place of the mask.
    static func unmasked(_ text: String, original: String) -> String {
        guard text.contains(mask), let pw = password(original) else { return text }
        return text.replacingOccurrences(of: mask, with: pw)
    }

    static func replacingHost(_ url: String, with host: String) -> String {
        guard var p = parts(url) else { return url }
        p.host = host
        return join(p)
    }

    /// New login where the address has one; nil keeps that part as it is.
    static func replacingLogin(_ url: String, user: String?, password: String?) -> String {
        guard user != nil || password != nil else { return url }
        var s = url
        if var p = parts(s), let info = p.userinfo {
            let colon = info.firstIndex(of: ":")
            let oldUser = colon.map { String(info[..<$0]) } ?? info
            let oldPassword = colon.map { String(info[info.index(after: $0)...]) }
            let u = user.map { $0.addingPercentEncoding(withAllowedCharacters: .urlUserAllowed) ?? $0 } ?? oldUser
            let pw = password.map { encodedPassword($0) } ?? oldPassword
            p.userinfo = u + (pw.map { ":" + $0 } ?? "")
            s = join(p)
        }
        // No login in the address yet: it goes in front of the host.
        if var p = parts(s), p.userinfo == nil, s.firstMatch(of: queryUser) == nil, s.firstMatch(of: queryPassword) == nil,
           let user, !user.isEmpty {
            p.userinfo = (user.addingPercentEncoding(withAllowedCharacters: .urlUserAllowed) ?? user)
                + (password.map { ":" + encodedPassword($0) } ?? "")
            return join(p)
        }
        let unreserved = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
        if let user, s.firstMatch(of: queryUser) != nil {
            s = s.replacing(queryUser) { $0.1 + (user.addingPercentEncoding(withAllowedCharacters: unreserved) ?? user) }
        }
        if let password, s.firstMatch(of: queryPassword) != nil {
            s = s.replacing(queryPassword) { $0.1 + (password.addingPercentEncoding(withAllowedCharacters: unreserved) ?? password) }
        }
        return s
    }

    private static func encodedPassword(_ password: String) -> String {
        var allowed = CharacterSet.urlPasswordAllowed
        allowed.remove(charactersIn: "@:/?#[]")
        return password.addingPercentEncoding(withAllowedCharacters: allowed) ?? password
    }

    /// Frigate can open it with ffprobe: a camera address without a {FRIGATE_*} variable.
    static func canProbe(_ url: String) -> Bool {
        guard let scheme = parts(url)?.scheme.lowercased() else { return false }
        return ["rtsp", "rtsps", "rtmp", "http", "https"].contains(scheme) && !url.contains("{")
    }
}
