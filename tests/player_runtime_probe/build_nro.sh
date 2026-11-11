#!/usr/bin/env bash
set -euo pipefail
: "${PLAY_WORK:?}"
IMAGE=devkitpro/devkita64@sha256:82575ea78651b530b2e232bb3799cfd1fe331514e053d5f724bb4b28191fb79d
docker pull "$IMAGE"
docker run --rm --network none --cap-drop ALL --security-opt no-new-privileges \
  --user "$(id -u):$(id -g)" --read-only --tmpfs /tmp:rw,nosuid,size=512m \
  --mount "type=bind,src=$PWD/tests/swkbd_android_probe,dst=/input,readonly" \
  --mount "type=bind,src=$PWD/tests/sdl_android_surface,dst=/graphics,readonly" \
  --mount "type=bind,src=$PWD/tests/guest_tls_probe,dst=/tls,readonly" \
  --mount "type=bind,src=$PWD/tests/guest_network_probe,dst=/network,readonly" \
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
    /opt/devkitpro/devkitA64/bin/aarch64-none-elf-gcc \
      -specs=/opt/devkitpro/libnx/switch.specs -march=armv8-a+crc+crypto \
      -mtune=cortex-a57 -mtp=soft -fPIE -O2 -Wall -Wextra \
      -I/opt/devkitpro/libnx/include /tls/main.c \
      -L/opt/devkitpro/libnx/lib -lnx -o /output/guest-tls.elf
    nacptool --create "CI TLS probe" "Symbiosis CI" "1.0.0" /output/guest-tls.nacp
    elf2nro /output/guest-tls.elf /output/guest-tls.nro \
      --nacp=/output/guest-tls.nacp --icon=/opt/devkitpro/libnx/default_icon.jpg
    sha256sum /output/guest-tls.nro
    /opt/devkitpro/devkitA64/bin/aarch64-none-elf-gcc \
      -specs=/opt/devkitpro/libnx/switch.specs -march=armv8-a+crc+crypto \
      -mtune=cortex-a57 -mtp=soft -fPIE -O2 -Wall -Wextra \
      -I/opt/devkitpro/libnx/include /network/main.c \
      -L/opt/devkitpro/libnx/lib -lnx -o /output/guest-network.elf
    nacptool --create "CI network probe" "Symbiosis CI" "1.0.0" /output/guest-network.nacp
    elf2nro /output/guest-network.elf /output/guest-network.nro \
      --nacp=/output/guest-network.nacp --icon=/opt/devkitpro/libnx/default_icon.jpg
    sha256sum /output/guest-network.nro
    SDL_CONFIG=/opt/devkitpro/portlibs/switch/bin/sdl2-config
    /opt/devkitpro/devkitA64/bin/aarch64-none-elf-gcc \
      -specs=/opt/devkitpro/libnx/switch.specs -march=armv8-a+crc+crypto \
      -mtune=cortex-a57 -mtp=soft -fPIE -O2 -Wall -Wextra -D__SWITCH__ -DSDL_DIAGNOSTIC_FRAMES=2400 -DSDL_DIAGNOSTIC_HID -DSDL_DIAGNOSTIC_MARKERS \
      -I/opt/devkitpro/libnx/include $("$SDL_CONFIG" --cflags) /graphics/main.c \
      -L/opt/devkitpro/libnx/lib $("$SDL_CONFIG" --libs) -lm -o /output/graphics.elf
    nacptool --create "CI graphics probe" "Symbiosis CI" "1.0.0" /output/graphics.nacp
    elf2nro /output/graphics.elf /output/graphics.nro \
      --nacp=/output/graphics.nacp --icon=/opt/devkitpro/libnx/default_icon.jpg
    sha256sum /output/graphics.nro
  '

# Upstream open-source GPU example, pinned by full revision. This guest is
# built for the diagnostic only; it never enters an app or release artifact.
mkdir -p "$PLAY_WORK/deko-example"
git init -q "$PLAY_WORK/switch-examples"
git -C "$PLAY_WORK/switch-examples" remote add origin https://github.com/switchbrew/switch-examples.git
git -C "$PLAY_WORK/switch-examples" fetch -q --depth 1 origin 669786898205b7beb25ff1731e72982e6d0397d3
git -C "$PLAY_WORK/switch-examples" checkout -q --detach FETCH_HEAD
[ "$(git -C "$PLAY_WORK/switch-examples" rev-parse HEAD)" = 669786898205b7beb25ff1731e72982e6d0397d3 ]
cp -r "$PLAY_WORK/switch-examples/graphics/deko3d/deko_basic/." "$PLAY_WORK/deko-example/"
python3 tests/player_runtime_probe/instrument_deko_example.py "$PLAY_WORK/deko-example/source/main.c"
docker run --rm --network none --cap-drop ALL --security-opt no-new-privileges \
  --user "$(id -u):$(id -g)" --read-only --tmpfs /tmp:rw,nosuid,size=512m \
  --mount "type=bind,src=$PLAY_WORK/deko-example,dst=/deko-example" \
  --workdir /deko-example --entrypoint /bin/bash "$IMAGE" -euc '
    command -v uam
    make -j2
    test -s deko-example.nro
    sha256sum deko-example.nro romfs/shaders/*.dksh
  '
cp "$PLAY_WORK/deko-example/deko-example.nro" "$PLAY_WORK/shader.nro"
