# Antigravity Android

Native Android client for Antigravity desktop agent orchestrator, built with modern Android development practices (Kotlin, Jetpack Compose, Material 3, and Coroutines).

## Overview

Antigravity Android provides mobile access and control for the Antigravity desktop swarm environment:
- **Real-Time Swarm Monitoring**: Connects to the local Antigravity orchestrator service via Connect-RPC over WebSockets.
- **Session Lifecycle Management**: Start, monitor, and manage agent cascades directly from your mobile device.
- **Monospace Streaming Logs**: High-performance virtualized terminal display for agent execution and tool invocations.
- **In-App Update Engine**: Automatic updates delivered seamlessly from GitHub Releases with streaming SHA-256 validation.

## Tech Stack
- **Language**: Kotlin 2.0.21
- **UI Framework**: Jetpack Compose (BOM 2024.10.01) with Material 3
- **Architecture**: Clean Architecture / Unidirectional Data Flow (MVI/UDF)
- **Networking**: OkHttp 4.12.0 (WebSocket, SSE, Streaming), Kotlinx Serialization 1.7.3
- **Min SDK**: API 26 (Android 8.0)
- **Target / Compile SDK**: API 35 (Android 15)

## Building the Project

### Prerequisites
- JDK 17
- Android SDK (API 35, Build-Tools 35.0.0)

### Local Build Commands
```bash
# Run unit tests
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

## CI/CD Pipeline
Every push and pull request to `main` runs automated tests and builds APK artifacts on GitHub Actions. Releases are automatically generated upon tagging (`v*`).
