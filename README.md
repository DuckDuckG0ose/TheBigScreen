# TheBigScreen — Live Minecraft Video Screens

TheBigScreen is a Paper 1.21.x plugin that lets you render live RTMP video streams onto in-game screens constructed of virtual maps and item frames. All rendering is sent client-side via packets only using ProtocolLib, keeping your server's physical world files completely clean.

## Performance & Hosting Recommendation
> [!WARNING]
> For the best performance, keep stream FPS and resolution low. Host the stream on a high-bandwidth server, or use a separate Velocity/BungeeCord server to handle the video traffic. High-resolution or high-FPS streams, limited bandwidth, or running the stream on an already busy game server can cause lag for players.

## Features
- **Low Impact**: Multi-threaded decode-convert-send pipeline (keeps work off the server thread).
- **Network Efficient**: Diff-rendering checks only send map packet updates for tiles whose contents have changed.
- **Client-Side Spawn**: No physical entities are added to the world.
- **Proximity Filtering**: Automatically starts/stops packet streams for players moving within/out of the configured distance (default: 64 blocks).
- **Auto Reconnect**: Seamlessly handles RTMP stream dropouts and attempts reconnections.

---

## Commands & Permissions
Admin commands require the `videoscreen.admin` permission node (defaults to server operators).

- `/videoscreen create <name> <width> <height>`  
  Creates a virtual screen grid of `width` × `height` map tiles anchored to the wall face you are looking at.
- `/videoscreen start <name> <rtmp-url>`  
  Begins streaming the specified RTMP URL to the screen.
- `/videoscreen stop <name>`  
  Stops streaming and clears the screen display.
- `/videoscreen delete <name>`  
  Stops the stream and removes the screen from the registry.
- `/videoscreen list`  
  Lists all defined screens, their dimensions, status, and active stream URL.

---

## Configuration (`config.yml`)
Configure fake map ID allocations, FPS targets, retry logic, and render radius in the `config.yml` file.

```yaml
fakeMapIdStart: 10000   # Starting ID for packet-only map allocations
targetFps: 15           # Target video frame rate
maxQueueDepth: 2        # Frame buffer backpressure capacity
reconnectAttempts: 5    # RTMP stream reconnection attempts
reconnectDelayMs: 3000  # Milliseconds to wait between retries
lutBuildOnStartup: true # Generate color conversion table on enable
renderRadiusBlocks: 64  # Block range within which players receive updates
hideFrameBorder: true   # Set fake item frames to be invisible
```

---

## Multi-Platform Compilation

FFmpeg native binaries are platform-dependent. The build detects your compile-time OS to build a lean shadowed JAR (~28MB) instead of pulling ~1GB of multiple OS binaries.

### Build Instructions
Run the shadow compilation on your target hosting platform:

```bash
# Clean and compile a production-ready shadowed JAR
./gradlew clean shadowJar
```
The output will be generated at `build/libs/watchparty-1.0.0-SNAPSHOT.jar`.

### CI/CD Deployment (GitHub Actions Matrix)
To compile correct binaries for multiple OS environments automatically, use this GitHub Actions configuration:

```yaml
name: Build WatchParty Plugin

on: [push, pull_request]

jobs:
  build:
    strategy:
      matrix:
        os: [ubuntu-latest, windows-latest]
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'gradle'
      - name: Build with Gradle
        run: ./gradlew shadowJar
      - name: Upload Artifact
        uses: actions/upload-artifact@v4
        with:
          name: watchparty-${{ matrix.os }}
          path: build/libs/watchparty-*.jar
```
