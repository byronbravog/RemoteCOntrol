#!/bin/zsh
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/PresenterRemote.app"
ICONSET="$ROOT/AppIcon.iconset"

swift build --package-path "$ROOT" -c release
rm -rf "$ICONSET" "$APP"
mkdir -p "$ICONSET" "$APP/Contents/MacOS" "$APP/Contents/Resources"

for size in 16 32 128 256 512; do
    magick -background none "$ROOT/AppIcon.svg" -resize "${size}x${size}" "$ICONSET/icon_${size}x${size}.png"
    double=$((size * 2))
    magick -background none "$ROOT/AppIcon.svg" -resize "${double}x${double}" "$ICONSET/icon_${size}x${size}@2x.png"
done

iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"
cp "$ROOT/.build/release/PresenterRemote" "$APP/Contents/MacOS/PresenterRemote"
cp "$ROOT/Info.plist" "$APP/Contents/Info.plist"
chmod +x "$APP/Contents/MacOS/PresenterRemote"
touch "$APP"
echo "Creada: $APP"