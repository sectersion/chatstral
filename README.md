# Chatstral

AI-powered chat moderation plugin for Paper Minecraft servers using local LLM inference via llama.cpp.

## Features

- **Local LLM Moderation**: Runs a lightweight 3B parameter model (Shieldstral) locally via llama-server
- **Instant Blacklist Filter**: Fast keyword/regex blocking with configurable word lists
- **AI Scoring**: Probability-based harmful content detection using logprob analysis
- **Async Processing**: Non-blocking chat evaluation to maintain server performance
- **Download Progress**: Visual progress bars when downloading model files

## Requirements

- Paper Minecraft server (1.21+)
- Java 25
- ~2GB RAM for model inference

### Supported Platforms
- Windows x64
- Linux x64 (Ubuntu)
- Linux arm64 (Ubuntu)
- macOS x64
- macOS arm64 (Apple Silicon)

The plugin auto-detects your OS and downloads the appropriate llama-server binary.

## Installation

1. Download the latest release JAR
2. Place in server's `plugins/` folder
3. Start/restart the server
4. The plugin will automatically download:
   - `llama-server` (platform-specific, ~40-50MB)
   - `Shieldstral-1.0-3B-Q3_K_M.gguf` model (~1.3GB)

## Configuration

Edit `plugins/chatstral/config.yml`:

```yaml
ai-filter-enabled: true   # Enable/disable AI filtering
ai-threshold: 0.5         # Sensitivity threshold (0.0-1.0, higher = stricter)
cooldown-ms: 1000         # Per-player cooldown between checks
llama-port: 8000          # Port for llama-server HTTP API
```

### Blacklist

Edit `plugins/chatstral/blacklist.txt`:

```
# Words (case-insensitive, one per line)
badword1
badword2

# Regex patterns
regex:\[spam\]
regex:(.)\1{4,}
```

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/chatstral reload` | Reload configuration and blacklist | `chatstral.admin` |
| `/chatstral status` | Check model and filter status | `chatstral.admin` |
| `/cs`, `/cfilter` | Aliases for `/chatstral` | - |

### Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `chatstral.bypass` | Skip all chat filtering | OP |
| `chatstral.admin` | Admin commands | OP |

## Architecture

```
ChatFilter.java        - Bukkit event listener, cooldown management
ShieldstralClient.java - HTTP client for llama-server API, score parsing, batch processing
ModelManager.java      - Downloads and manages llama-server lifecycle
Blacklist.java         - Word/regex pattern matching
Chatstral.java         - Main plugin class, command registration
```

### AI Detection Flow

1. Player sends chat message
2. Blacklist check (instant reject if matched)
3. If AI enabled: message queued for batch processing
4. Messages batched (up to 8) and sent to llama-server every 100ms
5. Server returns logprobs for "yes"/"no" tokens
6. Score calculated: `P(yes) = exp(logprob_yes) / (exp(logprob_yes) + exp(logprob_no))`
7. If score > threshold, message is blocked

## Troubleshooting

**Model download slow**: The ~1.3GB model is downloaded from HuggingFace. Consider pre-downloading and placing in `plugins/chatstral/models/`.

**High latency on chat**: First message may be slow as llama-server warms up. Subsequent checks are faster due to context caching.

**Messages not appearing**: Check server logs for llama-server errors. Ensure port 8000 is available.

## Building

```bash
./gradlew build
```

Output JAR in `build/libs/`.

## License

MIT
