#!/bin/bash
# Cross-platform build script for CCN Backend

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="$BACKEND_DIR/dist"

# Version from git tag or commit
VERSION=${VERSION:-$(git describe --tags --always 2>/dev/null || echo "dev")}

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[BUILD]${NC} $1"; }
log_success() { echo -e "${GREEN}[OK]${NC} $1"; }

# Platforms to build
PLATFORMS=(
    "darwin/amd64"    # macOS Intel
    "darwin/arm64"    # macOS Apple Silicon
    "linux/amd64"     # Linux x64
    "linux/arm64"     # Linux ARM64
    "windows/amd64"   # Windows x64
)

mkdir -p "$OUTPUT_DIR"

cd "$BACKEND_DIR"

for platform in "${PLATFORMS[@]}"; do
    GOOS=${platform%/*}
    GOARCH=${platform#*/}

    output_name="ccn-backend-${GOOS}-${GOARCH}"
    if [[ "$GOOS" == "windows" ]]; then
        output_name="${output_name}.exe"
    fi

    log_info "Building $GOOS/$GOARCH..."

    GOOS=$GOOS GOARCH=$GOARCH CGO_ENABLED=0 go build \
        -ldflags="-s -w -X main.version=$VERSION" \
        -o "$OUTPUT_DIR/$output_name" \
        ./cmd/server

    log_success "$output_name"
done

# Create archives
log_info "Creating archives..."

cd "$OUTPUT_DIR"

for platform in "${PLATFORMS[@]}"; do
    GOOS=${platform%/*}
    GOARCH=${platform#*/}

    binary="ccn-backend-${GOOS}-${GOARCH}"
    if [[ "$GOOS" == "windows" ]]; then
        binary="${binary}.exe"
    fi

    archive_name="ccn-backend-${VERSION}-${GOOS}-${GOARCH}"

    if [[ "$GOOS" == "windows" ]]; then
        zip -q "${archive_name}.zip" "$binary"
        log_success "${archive_name}.zip"
    else
        tar -czf "${archive_name}.tar.gz" "$binary"
        log_success "${archive_name}.tar.gz"
    fi
done

# Generate checksums
log_info "Generating checksums..."
if command -v sha256sum &> /dev/null; then
    sha256sum *.tar.gz *.zip > checksums.txt
elif command -v shasum &> /dev/null; then
    shasum -a 256 *.tar.gz *.zip > checksums.txt
fi

log_success "Build complete! Files in $OUTPUT_DIR"
ls -lh "$OUTPUT_DIR"
