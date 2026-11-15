import Foundation

/// The keys of a JSON object in the order the server wrote them. JSONSerialization and
/// JSONDecoder lose that order, but Frigate gives it meaning: the first of a camera's
/// `live.streams` is the stream live view opens with.
enum JSONKeyOrder {
    /// Keys of the object at `path` ("cameras", "carport", "live", "streams"); nil when it is not an object.
    static func keys(in data: Data, at path: [String]) -> [String]? {
        var scanner = Scanner(bytes: [UInt8](data))
        return scanner.keys(at: path[...])
    }

    private struct Scanner {
        let bytes: [UInt8]
        var i = 0

        init(bytes: [UInt8]) { self.bytes = bytes }

        mutating func keys(at path: ArraySlice<String>) -> [String]? {
            skipSpace()
            guard i < bytes.count, bytes[i] == UInt8(ascii: "{") else { return nil }
            i += 1
            var found: [String] = []
            while true {
                skipSpace()
                guard i < bytes.count else { return nil }
                if bytes[i] == UInt8(ascii: "}") { i += 1; return path.isEmpty ? found : nil }
                if bytes[i] == UInt8(ascii: ",") { i += 1; continue }
                guard let key = string() else { return nil }
                skipSpace()
                guard i < bytes.count, bytes[i] == UInt8(ascii: ":") else { return nil }
                i += 1
                if let next = path.first {
                    if key == next { return keys(at: path.dropFirst()) }
                    guard skipValue() else { return nil }
                } else {
                    found.append(key)
                    guard skipValue() else { return nil }
                }
            }
        }

        mutating func skipSpace() {
            while i < bytes.count, [0x20, 0x09, 0x0A, 0x0D].contains(bytes[i]) { i += 1 }
        }

        mutating func string() -> String? {
            guard i < bytes.count, bytes[i] == UInt8(ascii: "\"") else { return nil }
            let start = i
            i += 1
            while i < bytes.count, bytes[i] != UInt8(ascii: "\"") { i += bytes[i] == UInt8(ascii: "\\") ? 2 : 1 }
            guard i < bytes.count else { return nil }
            i += 1
            return try? JSONSerialization.jsonObject(with: Data(bytes[start..<i]), options: .fragmentsAllowed) as? String
        }

        /// Steps over one value of any kind.
        mutating func skipValue() -> Bool {
            skipSpace()
            guard i < bytes.count else { return false }
            switch bytes[i] {
            case UInt8(ascii: "\""): return string() != nil
            case UInt8(ascii: "{"), UInt8(ascii: "["):
                var depth = 0
                while i < bytes.count {
                    switch bytes[i] {
                    case UInt8(ascii: "\""): guard string() != nil else { return false }; continue
                    case UInt8(ascii: "{"), UInt8(ascii: "["): depth += 1
                    case UInt8(ascii: "}"), UInt8(ascii: "]"):
                        depth -= 1
                        if depth == 0 { i += 1; return true }
                    default: break
                    }
                    i += 1
                }
                return false
            default:
                while i < bytes.count, ![UInt8(ascii: ","), UInt8(ascii: "}"), UInt8(ascii: "]")].contains(bytes[i]) { i += 1 }
                return true
            }
        }
    }
}
