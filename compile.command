#!/bin/bash
cd "$(dirname "$0")"
echo "Compiling the mod..."
./gradlew build
echo ""
echo "Compilation finished. Press [Enter] to exit..."
read
