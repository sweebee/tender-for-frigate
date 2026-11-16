# Reply to App Review: com.apple.security.network.server (macOS)

Paste this as the reply in App Store Connect, and in App Review Information > Notes.

---

Hello,

The app needs com.apple.security.network.server for a local playback proxy. The proxy listens on the loopback address only (127.0.0.1, bound with NWListener's requiredLocalEndpoint). It never accepts connections from other devices.

Why it is needed: Tender for Frigate plays recordings from the user's own Frigate video server as HLS in AVPlayer. Many security cameras send HEVC video tagged "hev1", and Frigate passes that tag through unchanged. AVPlayer only plays HEVC over HLS when it is tagged "hvc1", so without the proxy these recordings stay black. The proxy forwards AVPlayer's requests to the user's Frigate server and rewrites the tag in the init segments. It also adds an EXT-X-START to the playlist, so playback can begin at a chosen time.

AVAssetResourceLoader cannot be used instead: it does not serve HLS media and init segments, and those are the parts that need the rewrite.

How to see it: open a camera, tap Playback (or open an event in Review), and the recording plays through http://127.0.0.1:<port>/…

Without this entitlement the sandbox blocks the listener, and recordings from these cameras do not play on the Mac.

Thank you.
