#!/usr/bin/env bash
set -euo pipefail
: "${PLAY_WORK:?}"
IMAGE=devkitpro/devkita64@sha256:82575ea78651b530b2e232bb3799cfd1fe331514e053d5f724bb4b28191fb79d
docker pull "$IMAGE"
docker run --rm --network none --cap-drop ALL --security-opt no-new-privileges \
  --user "$(id -u):$(id -g)" --read-only --tmpfs /tmp:rw,nosuid,size=512m \
  --mount "type=bind,src=$PWD/tests/swkbd_android_probe,dst=/input,readonly" \
  --mount "type=bind,src=$PLAY_WORK,dst=/output" --entrypoint /bin/bash "$IMAGE" -euc '
    /opt/devkitpro/devkitA64/bin/aarch64-none-elf-gcc \
      -specs=/opt/devkitpro/libnx/switch.specs -march=armv8-a+crc+crypto \
      -mtune=cortex-a57 -mtp=soft -fPIE -O2 -Wall -Wextra \
      -I/opt/devkitpro/libnx/include /input/main.c \
      -L/opt/devkitpro/libnx/lib -lnx -o /output/swkbd.elf
    nacptool --create "CI keyboard probe" "Symbiosis CI" "1.0.0" /output/swkbd.nacp
    elf2nro /output/swkbd.elf /output/swkbd.nro \
      --nacp=/output/swkbd.nacp --icon=/opt/devkitpro/libnx/default_icon.jpg
    sha256sum /output/swkbd.nro
  '
